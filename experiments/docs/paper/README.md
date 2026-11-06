# TD(18) paper draft

This directory contains the submission-oriented working paper for the exact
`TD(18)` computation.

- [`main.tex`](main.tex) contains the mathematical reduction, generalized
  algorithms, implementation invariants, validation, benchmark, and final-run
  certificate requirements.
- [`references.bib`](references.bib) contains the literature cited by the
  draft, including the toric/dihedral results, the odd-cycle lower bound, and
  the `TD(16)` proof.
- [`Makefile`](Makefile) builds the PDF with `latexmk`.

Build from this directory with:

```bash
make
```

or directly with:

```bash
latexmk -pdf -interaction=nonstopmode -halt-on-error main.tex
```

The red status box in the paper is intentional. Do not disable
`\draftstatustrue`, change the target theorem to an unconditional
`TD(18)=10` theorem, or replace the `PENDING` final-run fields until the
complete `E(18,11)` run and its verification artifacts have been archived.

After the final run:

1. Fill the final-run table only from the archived result, log, manifests, and
   hashes.
2. Record the exact source revision and software/hardware stack.
3. Add the independent verification procedure and its outcome.
4. Replace the conditional status language with the actual branch proved by
   the computation.
5. Re-run all CPU/GPU audits and compile the paper from a clean checkout.

The draft has been built with `latexmk` in the local development environment.
The generated PDF and auxiliary files are intentionally ignored; rebuild them
from the committed source when reviewing or archiving a revision.
