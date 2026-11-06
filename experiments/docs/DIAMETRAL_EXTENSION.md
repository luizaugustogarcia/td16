# Extending diametral sets by deletion and insertion

Write `d(p)` for block-transposition distance, `r(n) = TD(n)`, and
`D(n) = {p in S_n : d(p) = r(n)}`. A deletion always includes standardization.
Lemma 2.4 of João Araújo, Wolfram Bentz, Peter J. Cameron, and Luiz Silva,
*Minimal Excluded Permutations in a Model of Genome Evolution* (unpublished
manuscript, 14 October 2026; attached as *Genetics 14 Oct 2026.pdf*), proves

```text
d(p - x) <= d(p) <= d(p - x) + 1     for every symbol x.
```

Thus, if the deletion distances are known,

```text
max_x d(p - x) <= d(p) <= 1 + min_x d(p - x).
```

If the deletion distances include two different values, these bounds determine
`d(p)` exactly. If all deletion distances equal `q`, the only possibilities are
`q` and `q+1`; an additional exact decision is necessary.

## A complete step when the diameter increases

Suppose `r(n) = r(n-1)+1`, and put `r = r(n-1)`. Every deletion of a member of
`D(n)` lies in `D(n-1)`. In particular, deleting the largest symbol does.
Consequently the following algorithm is complete:

```text
for each p in D(n-1):
    for each of the n positions:
        q = insert the largest symbol n into p at that position
        reject q if any standardized deletion is absent from D(n-1)
        reject q if an exact search sorts q in at most r moves
        otherwise emit q
```

There are exactly `n*|D(n-1)|` initial candidates, with no duplicates in the
unquotiented enumeration. The deletion condition is necessary but not sufficient.
A negative bounded-search result must mean exhaustive failure, not a timeout or
unsuccessful heuristic. The deletion bound already supplies `d(q) <= r+1`.

## A complete step when the diameter stays constant

Suppose `r(n) = r(n-1) = r`. Define `I(A)` to be all permutations obtained by
inserting one symbol of any rank at any position into a member of `A`.
Let `B(k)` be the forbidden-pattern basis of the permutations sortable in at
most `k` moves, as in the attached manuscript. Then the exact disjoint union is

```text
D(n) = I(D(n-1)) disjoint-union (B(r-1) intersect S_n).
```

The first part is automatic by restriction monotonicity and the known diameter.
Every member of the second part has distance `r` and every deletion has distance
`r-1`. To generate the second part by maximum insertion, use the entire exact
distance-`r-1` layer in `S_(n-1)`, require all deletions to have that distance,
then reject candidates sortable in `r-1` moves. `D(n-1)` alone does not supply
this lower layer.

For example, the reverse permutation on 14 symbols has distance 8; every
deletion is the reverse permutation on 13 symbols, of distance 7. It belongs to
`B(7)` and cannot be obtained by insertion into `D(13)`.

## One recurrence covering both cases

Let `T(n,k) = {p in S_n : d(p) >= k}`. To obtain `T(n,k)`, insert the maximum
into each member of `T(n-1,k-1)`, and for each resulting candidate:

1. Reject if any deletion is outside `T(n-1,k-1)`.
2. Accept if any deletion belongs to `T(n-1,k)`.
3. Otherwise every deletion has distance exactly `k-1`. Accept precisely when
   exhaustive search rules out sorting in at most `k-1` moves.

For a diameter increase, the lower tail is exactly the previous diametral set
and the upper tail is empty. At a constant diameter, the lower tail includes
the next distance layer as well as the diametral set.

This is an exact enumeration algorithm, not a polynomial-time guarantee. The
current [implementation](../README.md) persists only requested canonical exact-distance sets
and uses exact bounded CPU/GPU sorting decisions. The original small audit uses
independent BFS as its exact oracle. Persisted instances do not include sorting
witnesses. It requests D(n) in order and generates missing support sets lazily,
using E(n,d) from E(n-1,d-1) and E(n-1,d). It does not precompute a speculative
closure from the final horizon. TD(18) is treated as unknown: distance 11 is
tried first, and distance-10 dependencies are requested only if that completed
set is empty.

The same inequality is now also used inside every GPU bounded search, for any
degree `n`: if a degree-`n-1` exact table or completed membership bitmap is
resident, a root prepass ranks all standardized one-symbol deletions. A deletion
known to be sortable within `r-1` proves the root sortable within `r`; an exact
deletion distance above `r` proves failure at that budget. Inconclusive roots
continue through the complete DFS. This prepass reuses already-loaded layers
and neither assumes `n=18` nor changes which layers the dependency plan builds.

Two further one-sided reductions reuse the exact bounded-search data. A
qualifying `+2` move creates a fixed point; contracting its fixed-point edge
preserves the child's distance, so a degree-`n-1` lookup at most `r-1` proves
the root sortable within `r`. The resident GPU path scans all qualifying moves.
The persisted-store path queries only the first qualifying contracted key and
continues with exact DFS if that lookup is inconclusive.

The oriented-unicycle reduction from the route-O proof is general for every
odd support size `N>=3`: a realizable oriented unicycle that evades the
qualifying criterion has an `(N+1)/2`-move support-clearing sequence. The GPU
tests this at every root and accepts only when that cost fits the budget. For
the largest required support, `N=19`, the complete normalized-cycle audit
visited 9,304,845 nodes and left three cycles, two oriented orbit
representatives. Both oriented representatives are realizable and have
verified ten-move support-clearing sequences; all unrecognized cases remain
with the exhaustive DFS.

When the goal is solely TD(18), the distance-10 branch is unnecessary. An empty
distance-11 set establishes the upper bound 10; monotonicity from TD(17)=10
supplies the matching lower bound. The `diameter` mode implements that decision
and stops at the first distance-11 example if one exists. Complete smaller
parent sets are still needed by the extension strategy.

## Consequences for degrees 14 through 18

The known diameters are `8, 8, 9, 9, 10` for degrees 13 through 17.
The current bounds for degree 18 are `10 <= TD(18) <= 11`.

| Target | Complete extension route |
| --- | --- |
| D(14) | Insertions of D(13), plus all degree-14 members of B(7) |
| D(15) | Diameter-increase algorithm from the complete D(14) |
| D(16) | Insertions of D(15), plus all degree-16 members of B(8) |
| D(17) | Diameter-increase algorithm from the complete D(16) |
| Degree 18, distance 11 | Diameter-increase algorithm from D(17) |

If the last search emits anything, `TD(18)=11` and its output is `D(18)`.
If it exhausts all candidates without emitting anything, `TD(18)=10`; then
`D(18)` consists of insertions of `D(17)` together with the degree-18 part of
`B(9)`.

Output size is a major constraint. The published distribution gives
`|D(13)|=427` and `|D(14)|=5,246,800,005`. All arbitrary-rank insertions into
`D(13)` supply at most `14^2 * 427 = 83,692` distinct candidates. Therefore at
least `5,246,716,313` members of `D(14)` are new minimal exclusions at radius 7.
An explicit-list computation already has billions of outputs at degree 14.
Symbolic representations, symmetry classes, or counts should be distinguished
from a complete explicit list; symmetry representatives also require correct
orbit weights and a completeness-preserving augmentation rule.

Sources: [Korchmaros, Table 5.2](https://arxiv.org/pdf/1504.02157#page=48)
for distance distributions; [Silva et al., Sections 1, 7, and 8](https://arxiv.org/html/2609.17493v3)
for the known diameters, reverse-permutation formula, status of TD(18), and the
qualifying/support-clearing framework; and Mühle and Nadeau,
[*A Poset Structure on the Alternating Group Generated by 3-Cycles*](https://doi.org/10.5802/alco.83),
for `ell_3=(N-c_odd)/2` and its cover characterization. The extension
recurrences and deletion prepass use Lemma 2.4 of the distinct Araújo--Bentz--
Cameron--Silva manuscript cited above.

## Reproducible JShell audit

```bash
bash experiments/run.sh audit
bash experiments/run.sh audit --max-n 10
```

The default checks degrees 2 through 9; the second command includes degree 10.
The Maven module also retains a JShell entrypoint; see [README.md](../README.md).
The audit uses packed words, Lehmer ranks, primitive arrays, and independent BFS
with every block transposition. It checks every distance-distribution entry
against the supplied table, compares the complete generated threshold sets
against BFS (not just counts), and checks the deletion bound on every permutation.
It intentionally limits the experiment to degree 10, not factorial-size runs
at the requested large degrees. No large-degree diametral set is claimed here.
