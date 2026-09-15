package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;
import br.unb.cic.tdp.permutation.MulticyclePermutation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CertificateCodecTest {
    @Test
    void codecRoundTripsTheCanonicalKeyAndOrderedTriples() {
        final var pair = threeCyclePair().canonicalRepresentative();
        final var moves = List.of(Cycle.of(0, 1, 2));

        final var key = CertificateCodec.keyFor(pair);
        assertArrayEquals(pair.getOmega().getOneLineNotation(),
                CertificateCodec.decodeKeyOmega(key, pair.getBeta().size()));
        assertEquals(2, key.length);
        assertEquals(moves, CertificateCodec.decodeValue(CertificateCodec.valueFor(moves)));
        assertEquals(2, CertificateCodec.valueFor(moves).length);
        assertArrayEquals(key, CertificateCodec.keyFor(pair));
        CertificateValidator.validate(pair, moves, 1);
    }

    @Test
    void validatorRejectsInapplicableAndOverlongCertificates() {
        final var pair = threeCyclePair().canonicalRepresentative();
        assertThrows(IllegalArgumentException.class,
                () -> CertificateValidator.validate(pair, List.of(Cycle.of(0, 2, 1)), 1));
        assertThrows(IllegalArgumentException.class,
                () -> CertificateValidator.validate(pair, List.of(Cycle.of(0, 1, 2)), 0));
        assertThrows(IllegalArgumentException.class,
                () -> CertificateCodec.decodeValue(new byte[]{0}));
    }

    private static CyclicTargetPair threeCyclePair() {
        return CyclicTargetPair.of(new MulticyclePermutation(Cycle.of(0, 1, 2)),
                Cycle.of(0, 1, 2));
    }
}
