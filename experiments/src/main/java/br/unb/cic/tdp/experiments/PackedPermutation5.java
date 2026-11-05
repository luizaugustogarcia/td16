package br.unb.cic.tdp.experiments;

import java.util.Arrays;

/** MSB-first five-bit symbols, with zero padding only at the end. */
final class PackedPermutation5 {
    static int bytes(int symbols) {
        if(symbols<1||symbols>19)throw new IllegalArgumentException("Symbol count must be 1..19");
        return (5*symbols+7)/8;
    }
    static void encode(byte[] symbols,int count,byte[] target,int offset) {
        int length=bytes(count);Arrays.fill(target,offset,offset+length,(byte)0);
        int seen=0;
        for(int i=0;i<count;i++) {
            int value=symbols[i];
            if(value<0||value>=count||(seen&(1<<value))!=0)throw new IllegalArgumentException("Not a permutation on 0.."+(count-1));
            seen|=1<<value;
            int bit=5*i,index=offset+(bit>>>3),shift=3-(bit&7);
            if(shift>=0)target[index]|=(byte)(value<<shift);
            else {target[index]|=(byte)(value>>>-shift);target[index+1]|=(byte)(value<<(8+shift));}
        }
    }
    static byte[] encode(byte[] symbols,int count) {
        byte[] result=new byte[bytes(count)];encode(symbols,count,result,0);return result;
    }
    static void decode(byte[] packed,int offset,int count,byte[] target) {
        int seen=0;
        for(int i=0;i<count;i++) {
            int bit=5*i,index=offset+(bit>>>3),shift=3-(bit&7),value;
            if(shift>=0)value=((packed[index]&255)>>>shift)&31;
            else value=(((packed[index]&255)<<-shift)|((packed[index+1]&255)>>>(8+shift)))&31;
            if(value>=count||(seen&(1<<value))!=0)throw new IllegalArgumentException("Invalid packed permutation");
            seen|=1<<value;target[i]=(byte)value;
        }
        int padding=8*bytes(count)-5*count;
        if(padding>0 && ((packed[offset+bytes(count)-1]&255)&((1<<padding)-1))!=0)
            throw new IllegalArgumentException("Nonzero packed padding");
    }
}
