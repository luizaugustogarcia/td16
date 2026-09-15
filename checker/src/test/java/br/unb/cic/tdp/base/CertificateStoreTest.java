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
import static org.junit.jupiter.api.Assertions.assertThrows;

class CertificateStoreTest {
    @Test
    void replaysStoredSequencesAndRejectsCorruption(@TempDir final Path directory) throws Exception {
        final var pair = CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2)).canonicalRepresentative();
        final var key = CertificateCodec.keyFor(pair);
        try (final var options = new Options().setCreateIfMissing(true);
             final var database = RocksDB.open(options, directory.toString())) {
            database.put(key, CertificateCodec.valueFor(List.of(Cycle.of(0, 1, 2))));
        }
        try (final var store = new CertificateStore(directory)) {
            assertEquals(1, store.find(pair, 1).size());
        }

        try (final var options = new Options(); final var database = RocksDB.open(options, directory.toString())) {
            database.put(key, new byte[]{0});
        }
        try (final var store = new CertificateStore(directory)) {
            assertThrows(IllegalArgumentException.class, () -> store.find(pair, 1));
        }
    }
}
