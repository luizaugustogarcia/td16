package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncCertificateWriterTest {
    @Test
    void writesACertificate(@TempDir final Path directory) throws Exception {
        final var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2)).canonicalRepresentative();
        final var move = Cycle.of(0, 1, 2);

        try (final var writer = new AsyncCertificateWriter(directory, 60)) {
            writer.enqueue(pair, List.of(move));
        }

        try (final var options = new Options(); final var database = RocksDB.openReadOnly(options, directory.toString())) {
            assertEquals(1, CertificateCodec.decodeValue(database.get(CertificateCodec.keyFor(pair))).size());
        }
    }
}
