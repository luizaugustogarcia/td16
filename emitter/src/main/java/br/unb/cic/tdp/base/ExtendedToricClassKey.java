package br.unb.cic.tdp.base;

import java.util.Objects;

/** Value key for the extended-toric class corresponding to a realizable bracelet. */
public record ExtendedToricClassKey(TwistedBraceletKey twistedBracelet) {

    public ExtendedToricClassKey {
        Objects.requireNonNull(twistedBracelet);
    }

    public static ExtendedToricClassKey of(final CyclicTargetPair pair) {
        return new ExtendedToricClassKey(pair.twistedBraceletKey());
    }

    @Override
    public String toString() {
        return twistedBracelet.toString();
    }
}
