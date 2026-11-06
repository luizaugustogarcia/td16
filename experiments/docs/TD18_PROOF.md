# Determining TD(18)

The experiment treats TD(18) as unknown. From TD(17)=10, deletion monotonicity
and the one-symbol insertion lemma give

```text
10 <= TD(18) <= 11.
```

Every degree-18 permutation at distance 11 has every standardized deletion at
distance 10. In particular, deletion of its maximum belongs to the complete
D(17). Thus extending every ordinary member of D(17) by maximum insertion
covers every possible distance-11 permutation. Expanding the full orbit of
each canonical parent before retaining canonical children preserves this
coverage; extended-toric symmetries preserve distance.

Candidates with a deletion below distance 10 already have distance at most
10. For the remaining candidates, an exact CanSort(10) decision suffices:

- One exhaustive failure to sort in ten moves supplies a distance-11 example
  and establishes TD(18)=11 using the upper bound.
- If the complete candidate search finishes with no example, TD(18)<=10 and
  hence TD(18)=10. No enumeration or count of D(18) at distance 10 is needed.

The `diameter` mode stops on the first verified example. Otherwise it checks
that parent and orbit-extension totals agree with the complete input manifest
and that every canonical candidate was settled before publishing an empty set.
Errors and interrupted searches never become an empty completed set. A
positive interrupted enumeration is not published as a complete set.

This can support a computer-assisted proof when the completeness argument,
distance-preserving symmetries, contraction rules, pruning bounds and exact
search implementation are justified and the completed computation is
reproducible. Small-degree tests validate implementation behavior; they do
not establish the degree-18 conclusion.

Current persistence contains canonical permutations and distances, without
sorting sequences. The result properties and logs are not an independently
checkable sorting certificate. An independent verification procedure and
archived source/build/input provenance would strengthen a publication; the
existing TD(16) computation describes such an emitter/checker separation in
[Section 7](https://arxiv.org/html/2609.17493v3#S7).

The optional degree-14 GPU bitmap represents only the complete exact set
E(14,8). Its population is checked against the source manifest after orbit
expansion, using 64-bit ranks, byte offsets and population counters. A bit
equal to one supplies distance 8. A bit equal to zero supplies the upper
bound 7, and never an exact smaller distance. This bound also applies after
the existing distance-preserving adjacency contractions in DFS. A partial
bitmap is never used. If the table is unavailable, search uses the previous
pattern tables and persistent membership index. This cache changes the
evaluation of valid bounds; it does not change which exact sets are required.

Cache selection is automatic per GPU. A larger four-bit table may include
E(14,7) alongside E(14,8) after both stores are complete. An absent entry then
proves distance at most 6, while retained entries supply their exact distance.
Missing lower-degree stores remain unknown rather than being treated as
complete tables. All table offsets and ordinary ranks are 64-bit. Caching
does not request new sets or change the extension dependencies.
