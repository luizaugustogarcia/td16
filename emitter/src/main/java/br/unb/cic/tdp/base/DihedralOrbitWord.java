package br.unb.cic.tdp.base;

import java.util.Objects;

/**
 * A standardized word reached by {@code r_shift}, or by
 * {@code r_shift s} when {@code reflected} is true.
 */
public record DihedralOrbitWord(
        StandardizedEncodingWord word,
        int shift,
        boolean reflected
) {
    public DihedralOrbitWord {
        Objects.requireNonNull(word);
    }
}
