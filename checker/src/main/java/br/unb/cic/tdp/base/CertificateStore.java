package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only certificate database used by the CPU-only proof executor. */
@Slf4j
public final class CertificateStore implements AutoCloseable {
    private static final String STATS_PERIOD_PROPERTY = "tdp.certificateStatsPeriodSeconds";
    private static final long DEFAULT_STATS_PERIOD_SECONDS = 10;

    static {
        RocksDB.loadLibrary();
    }

    /*
     * Certificate keys are read at most once. Keep every SST table reader open
     * so its preloaded index/filter metadata remains resident, but do not admit
     * the one-pass data blocks to the RocksDB block cache.
     */
    private final Options options = new Options()
            .setMaxOpenFiles(-1)
            .setTableFormatConfig(new BlockBasedTableConfig()
                    .setCacheIndexAndFilterBlocks(false));
    private final ReadOptions readOptions = new ReadOptions()
            .setVerifyChecksums(true)
            .setFillCache(false);
    private final RocksDB database;
    private final AtomicLong verifiedInWindow = new AtomicLong();
    private final ScheduledExecutorService statsReporter;
    private long windowStartedNanos;

    public CertificateStore(final Path directory) {
        final long statsPeriodSeconds = parseStatsPeriodSeconds();
        try {
            database = RocksDB.openReadOnly(options, directory.toAbsolutePath().toString());
        } catch (final RocksDBException failure) {
            readOptions.close();
            options.close();
            throw new IllegalStateException("Cannot open certificate database " + directory, failure);
        }
        windowStartedNanos = System.nanoTime();
        if (statsPeriodSeconds > 0) {
            statsReporter = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("certificate-verification-stats").factory());
            statsReporter.scheduleAtFixedRate(
                    this::logWindowStats, statsPeriodSeconds, statsPeriodSeconds, TimeUnit.SECONDS);
        } else {
            statsReporter = null;
        }
    }

    private static long parseStatsPeriodSeconds() {
        final var value = System.getProperty(
                STATS_PERIOD_PROPERTY, Long.toString(DEFAULT_STATS_PERIOD_SECONDS)).trim();
        final long period;
        try {
            period = Long.parseLong(value);
        } catch (final NumberFormatException failure) {
            throw new IllegalArgumentException(
                    STATS_PERIOD_PROPERTY + " must be a nonnegative integer", failure);
        }
        if (period < 0) {
            throw new IllegalArgumentException(
                    STATS_PERIOD_PROPERTY + " must be a nonnegative integer");
        }
        return period;
    }

    /**
     * A lookup is a proof obligation check, not a blind deserialization.  The
     * stored triple sequence is replayed from the class's canonical pair.
     */
    public List<Cycle> find(final CyclicTargetPair pair,
                            final int maximumMoves) {
        return find(new CyclicTargetPair[]{pair}, maximumMoves).getFirst();
    }

    /**
     * Looks up and independently replays a position-preserving batch of proof
     * obligations using one RocksDB multi-get operation.
     */
    public List<List<Cycle>> find(final CyclicTargetPair[] pairs,
                                  final int maximumMoves) {
        final var keys = new ArrayList<byte[]>(pairs.length);
        for (final var pair : pairs) {
            keys.add(CertificateCodec.keyFor(pair));
        }
        final List<byte[]> values;
        try {
            values = database.multiGetAsList(readOptions, keys);
        } catch (final RocksDBException failure) {
            throw new IllegalStateException("Cannot read certificate database", failure);
        }
        final var results = new ArrayList<List<Cycle>>(pairs.length);
        for (var index = 0; index < pairs.length; index++) {
            final var value = values.get(index);
            if (value == null) {
                results.add(List.of());
                continue;
            }
            final var moves = CertificateCodec.decodeValue(value);
            CertificateValidator.validate(pairs[index], moves, maximumMoves);
            verifiedInWindow.incrementAndGet();
            results.add(moves);
        }
        return results;
    }

    private void logWindowStats() {
        final long now = System.nanoTime();
        final long elapsedNanos = Math.max(1, now - windowStartedNanos);
        final long verified = verifiedInWindow.getAndSet(0);
        windowStartedNanos = now;
        final double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        final double throughput = verified / elapsedSeconds;
        log.info("Certificate verification stats (last {}s): verified={}, throughput={} witnesses/s",
                format(elapsedSeconds), verified, format(throughput));
    }

    private static String format(final double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    @Override
    public void close() {
        if (statsReporter != null) {
            statsReporter.shutdownNow();
        }
        database.close();
        readOptions.close();
        options.close();
    }
}
