package br.unb.cic.tdp.base;

import br.unb.cic.tdp.permutation.Cycle;

import java.util.ArrayList;
import java.util.List;

/** Compact on-disk encoding for normalized certificate pairs and witnesses. */
public final class CertificateCodec {
    private static final int SYMBOL_BITS = 5;
    private static final int SYMBOL_LIMIT = 1 << SYMBOL_BITS;
    private static final int TRIPLE_BITS = 3 * SYMBOL_BITS;

    private CertificateCodec() {
    }

    /**
     * Encodes the algebraic permutation of a normalized pair in one-line
     * notation. The image of each symbol occupies five bits, most significant
     * bit first.
     */
    public static byte[] keyFor(final CyclicTargetPair pair) {
        final var oneLine = pair.getOmega().getOneLineNotation();
        if (oneLine.length != pair.getBeta().size()) {
            throw new IllegalArgumentException(
                    "Certificate keys require an algebraic permutation on the full beta domain");
        }
        requireDomainSize(oneLine.length);

        final var encoded = new byte[byteLength((long) SYMBOL_BITS * oneLine.length)];
        var bitOffset = 0;
        for (final var image : oneLine) {
            bitOffset = writeSymbol(encoded, bitOffset, Byte.toUnsignedInt(image));
        }
        return encoded;
    }

    /** Decodes the one-line algebraic permutation stored in a key. */
    public static byte[] decodeKeyOmega(final byte[] encoded, final int domainSize) {
        requireDomainSize(domainSize);
        requirePayload(encoded, (long) SYMBOL_BITS * domainSize, "key");

        final var oneLine = new byte[domainSize];
        var bitOffset = 0;
        for (var symbol = 0; symbol < domainSize; symbol++) {
            oneLine[symbol] = (byte) readSymbol(encoded, bitOffset);
            bitOffset += SYMBOL_BITS;
        }
        return oneLine;
    }

    /**
     * Encodes the ordered move triples as consecutive fifteen-bit fields.
     * Each field stores its three symbols in their given order.
     */
    public static byte[] valueFor(final List<Cycle> moves) {
        if (moves.isEmpty()) {
            throw new IllegalArgumentException("A certificate must contain at least one move");
        }

        final var encoded = new byte[byteLength((long) TRIPLE_BITS * moves.size())];
        var bitOffset = 0;
        for (final var move : moves) {
            if (move.size() != 3) {
                throw new IllegalArgumentException("A certificate move must be a symbol triple");
            }
            bitOffset = writeSymbol(encoded, bitOffset, move.get(0));
            bitOffset = writeSymbol(encoded, bitOffset, move.get(1));
            bitOffset = writeSymbol(encoded, bitOffset, move.get(2));
        }
        return encoded;
    }

    /** Decodes the concatenated fifteen-bit move triples stored in a value. */
    public static List<Cycle> decodeValue(final byte[] encoded) {
        if (encoded == null || encoded.length == 0) {
            throw new IllegalArgumentException("Truncated certificate value");
        }

        final long capacityBits = (long) Byte.SIZE * encoded.length;
        final int moveCount = Math.toIntExact(capacityBits / TRIPLE_BITS);
        if (moveCount == 0) {
            throw new IllegalArgumentException("Truncated certificate value");
        }
        requirePayload(encoded, (long) TRIPLE_BITS * moveCount, "value");

        final var moves = new ArrayList<Cycle>(moveCount);
        var bitOffset = 0;
        for (var index = 0; index < moveCount; index++) {
            final int first = readSymbol(encoded, bitOffset);
            final int second = readSymbol(encoded, bitOffset + SYMBOL_BITS);
            final int third = readSymbol(encoded, bitOffset + 2 * SYMBOL_BITS);
            moves.add(Cycle.of(first, second, third));
            bitOffset += TRIPLE_BITS;
        }
        return List.copyOf(moves);
    }

    private static int writeSymbol(final byte[] encoded, final int bitOffset, final int symbol) {
        requireSymbol(symbol);
        for (var bit = 0; bit < SYMBOL_BITS; bit++) {
            final int valueBit = (symbol >>> (SYMBOL_BITS - 1 - bit)) & 1;
            final int outputBit = bitOffset + bit;
            encoded[outputBit / Byte.SIZE] |= (byte) (valueBit << (Byte.SIZE - 1 - outputBit % Byte.SIZE));
        }
        return bitOffset + SYMBOL_BITS;
    }

    private static int readSymbol(final byte[] encoded, final int bitOffset) {
        var symbol = 0;
        for (var bit = 0; bit < SYMBOL_BITS; bit++) {
            final int inputBit = bitOffset + bit;
            symbol = (symbol << 1)
                    | ((Byte.toUnsignedInt(encoded[inputBit / Byte.SIZE])
                    >>> (Byte.SIZE - 1 - inputBit % Byte.SIZE)) & 1);
        }
        return symbol;
    }

    private static void requirePayload(final byte[] encoded, final long payloadBits, final String kind) {
        if (encoded == null || encoded.length != byteLength(payloadBits)) {
            throw new IllegalArgumentException("Malformed certificate " + kind + " length");
        }
        final int paddingBits = (int) ((long) Byte.SIZE * encoded.length - payloadBits);
        if (paddingBits != 0 && (Byte.toUnsignedInt(encoded[encoded.length - 1])
                & ((1 << paddingBits) - 1)) != 0) {
            throw new IllegalArgumentException("Nonzero certificate " + kind + " padding");
        }
    }

    private static void requireDomainSize(final int size) {
        if (size <= 0 || size > SYMBOL_LIMIT) {
            throw new IllegalArgumentException(
                    "The compact certificate format supports domains of size 1.." + SYMBOL_LIMIT);
        }
    }

    private static void requireSymbol(final int symbol) {
        if (symbol < 0 || symbol >= SYMBOL_LIMIT) {
            throw new IllegalArgumentException(
                    "Certificate symbols must lie in 0.." + (SYMBOL_LIMIT - 1) + ": " + symbol);
        }
    }

    private static int byteLength(final long bitLength) {
        return Math.toIntExact((bitLength + Byte.SIZE - 1) / Byte.SIZE);
    }
}
