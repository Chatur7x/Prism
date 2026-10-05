#!/usr/bin/env python3
"""Generate the Delacroix PDF demo corpus.

Why this exists
---------------
`demo/corpus/` holds 24 markdown memos whose figures are cited in the README and
in `docs/evaluation.md`: 5 conflicts, 1 bound violation, 26 relations, 1
deliberately uninformative memo. Adding documents to that corpus would falsify
those numbers, so the PDF set is a *separate* corpus with its own planted
findings and its own expected counts.

Why PDF and not more markdown
-----------------------------
Because PRISM's PDF path is a real code path -- PDFBox 3 text extraction in
`FileTextExtractor` -- and until a PDF is actually uploaded it is untested
against a file this repository produced. A demo that only ever exercises the
text branch leaves the PDF branch as a claim rather than a demonstration.

What is planted, and why each one
--------------------------------
Contradictions require a predicate that is SINGLE, mutually exclusive and NOT
temporal (`PredicateDefinition.conflictsOnDifferentObjects`). From
`PredicateSemanticRegistry` those are exactly:

    reports_to, parent_organization, subsidiary_of, founded_by

`headquartered_in`, `chief_executive`, `located_in` and `part_of` are also
SINGLE but *temporal*, so two values may be sequential rather than
contradictory. The temporal ones are planted too, because whether they conflict
depends on effective times and that is worth observing rather than assuming.

The bound violation uses `sources_from`, which is BOUNDED at 3. Four asserted
values must be flagged.

Everything else uses MULTI predicates, which must NOT be flagged. That half of
the corpus is the more important half: a contradiction engine that flags
everything looks correct on a corpus of conflicts and is useless in production.

One memo yields nothing at all. A demo where every document produces findings
proves nothing about whether the system can tell silence from substance.

Usage
-----
    python scripts/make-demo-pdfs.py [--out demo/pdf-corpus]

Re-running overwrites in place; the output is deterministic, so the same corpus
produces the same findings on every machine.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from reportlab.lib.enums import TA_JUSTIFY
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
    from reportlab.lib.units import mm
    from reportlab.platypus import (
        PageBreak,
        Paragraph,
        SimpleDocTemplate,
        Spacer,
    )
except ImportError:  # pragma: no cover - environment problem, not logic
    sys.exit(
        "reportlab is required: python -m pip install reportlab\n"
        "(PDFBox on the server side cannot create these; something has to lay them out.)"
    )


# --------------------------------------------------------------------------
# The corpus. Each memo is (filename, title, classification, author, date,
# [(heading, [paragraph, ...]), ...]).
#
# Sentences in `Subject predicate Object.` form are the ones the offline fixture
# recognises. Prose around them exists so extraction is doing real work on real
# text rather than being handed a bare list of triples.
#
# ASCII only, deliberately: PDFBox reads a PDF byte stream, and a stray
# smart quote or em dash surviving as mojibake would be a confusing failure to
# debug in the Glass Box rather than an obvious one here.
# --------------------------------------------------------------------------

MEMOS: list[tuple[str, str, str, str, str, list[tuple[str, list[str]]]]] = [
    (
        "01-delacroix-q1-board-memo.pdf",
        "Delacroix Group - Q1 board memo",
        "Internal",
        "Corporate Secretariat",
        "14 February",
        [
            (
                "1. Reporting line",
                [
                    "Following the reorganisation approved in January, the group "
                    "reporting lines were simplified. The secretariat was asked to "
                    "record the position in a single place rather than across "
                    "three memoranda.",
                    "Delacroix Group reports_to Aurelia Holdings.",
                    "Delacroix Group parent_organization Aurelia Holdings.",
                    "Delacroix Group headquartered_in Harrowgate.",
                ],
            ),
            (
                "2. Portfolio",
                [
                    "The portfolio review confirmed the four investments the "
                    "board asked us to revisit. All four remain open and no "
                    "divestment was authorised this quarter.",
                    "Delacroix Group invests_in Verity Foods.",
                    "Delacroix Group invests_in Kestrel Components.",
                    "Delacroix Group invests_in Lumen Analytics.",
                    "Delacroix Group invests_in Saltmarsh Freight.",
                ],
            ),
            (
                "3. Items for the next meeting",
                [
                    "The audit committee asked for a written reconciliation of "
                    "the subsidiary register. The secretariat will circulate a "
                    "draft before the April session. No decision is expected at "
                    "this meeting.",
                ],
            ),
        ],
    ),
    (
        "02-delacroix-holding-company-review.pdf",
        "Delacroix Group - holding company review",
        "Internal",
        "Group Finance",
        "3 March",
        [
            (
                "1. Control relationship",
                [
                    "Finance revisited the control relationship after the "
                    "holding company register was reconciled against the "
                    "corporate filings. Two entries did not agree with the "
                    "original board minute and the difference is recorded here "
                    "rather than corrected in place, so that both readings "
                    "remain visible to the committee.",
                    "Delacroix Group reports_to Brightwater Partners.",
                    "Delacroix Group parent_organization Brightwater Partners.",
                    "Delacroix Group controls Verity Foods.",
                    "Delacroix Group controls Kestrel Components.",
                    "Delacroix Group controls Lumen Analytics.",
                ],
            ),
            (
                "2. Collaborations",
                [
                    "Three collaborative arrangements were in force during the "
                    "period. None of them implies control and none of them was "
                    "expected to.",
                    "Delacroix Group collaborates_with Aurelia Holdings.",
                    "Delacroix Group collaborates_with Saltmarsh Freight.",
                    "Delacroix Group collaborates_with Corvale Analytics.",
                ],
            ),
        ],
    ),
    (
        "03-verity-foods-ownership-note.pdf",
        "Verity Foods - ownership note",
        "Internal",
        "Company Secretary",
        "21 March",
        [
            (
                "1. Register entry",
                [
                    "The company secretary reviewed the register entry against "
                    "the certificate of incorporation. The entry has been "
                    "amended twice and the working copy used by the finance team "
                    "predates the most recent amendment.",
                    "Verity Foods subsidiary_of Delacroix Group.",
                    "Verity Foods headquartered_in Corvale.",
                ],
            ),
            (
                "2. Second reading",
                [
                    "A second copy of the note, circulated by the acquiring "
                    "counsel, records the holding differently. Both copies are "
                    "retained.",
                    "Verity Foods subsidiary_of Brightwater Partners.",
                ],
            ),
        ],
    ),
    (
        "04-kestrel-components-supply-base.pdf",
        "Kestrel Components - supply base",
        "Internal",
        "Procurement",
        "2 April",
        [
            (
                "1. Supplier register",
                [
                    "Procurement consolidated the consumables supply base over "
                    "the past two quarters. The register below is the working "
                    "copy and lists every supplier with an active framework "
                    "agreement.",
                    "Kestrel Components sources_from Vantage Materials.",
                    "Kestrel Components sources_from Pinnacle Freight.",
                    "Kestrel Components sources_from Halcyon Metals.",
                    "Kestrel Components sources_from Cobalt Tooling.",
                ],
            ),
            (
                "2. Operating responsibility",
                [
                    "Two operating entities are recorded against the Corvale "
                    "site. This is the arrangement inherited on acquisition and "
                    "has not been restructured.",
                    "Kestrel Components operated_by Delacroix Group.",
                    "Kestrel Components operated_by Brightwater Partners.",
                ],
            ),
        ],
    ),
    (
        "05-lumen-analytics-leadership.pdf",
        "Lumen Analytics - leadership and office",
        "Internal",
        "People Operations",
        "18 April",
        [
            (
                "1. Executive team",
                [
                    "People Operations maintains the executive register for "
                    "each operating company. Two entries were changed during "
                    "the period and the outgoing and incoming records were both "
                    "filed rather than overwritten.",
                    "Lumen Analytics chief_executive R. Ashworth.",
                    "Lumen Analytics located_in Halden.",
                ],
            ),
            (
                "2. Incoming appointment",
                [
                    "The incoming appointment was confirmed by the board on 11 "
                    "April and takes effect at the start of the next financial "
                    "year. The prior record is retained for the year-end audit "
                    "trail.",
                    "Lumen Analytics chief_executive M. Okonjo.",
                ],
            ),
        ],
    ),
    (
        "06-saltmarsh-freight-network.pdf",
        "Saltmarsh Freight - network and regulator",
        "Internal",
        "Network Planning",
        "6 May",
        [
            (
                "1. Regulators",
                [
                    "Each corridor is subject to a separate economic regulator. "
                    "Four filings are current and each names a different "
                    "authority.",
                    "Saltmarsh Freight regulated_by Corvale Transit Authority.",
                    "Saltmarsh Freight regulated_by Halden Rail Regulator.",
                    "Saltmarsh Freight regulated_by Port Authority of Brightwater.",
                    "Saltmarsh Freight regulated_by Northern Freight Board.",
                ],
            ),
            (
                "2. Corridors",
                [
                    "The corridor network is unchanged since the prior review "
                    "and no new operating region was added.",
                    "Saltmarsh Freight operates_in Corvale.",
                    "Saltmarsh Freight operates_in Halden.",
                    "Saltmarsh Freight operates_in Brightwater.",
                ],
            ),
        ],
    ),
    (
        "07-aurelia-holdings-alliance.pdf",
        "Aurelia Holdings - alliance summary",
        "Internal",
        "Secretariat",
        "9 May",
        [
            (
                "1. Alliances",
                [
                    "The alliance register lists every current alliance "
                    "arrangement. Alliances are reciprocal and none of them "
                    "implies ownership.",
                    "Aurelia Holdings allied_with Delacroix Group.",
                    "Aurelia Holdings allied_with Meridian Group.",
                    "Aurelia Holdings allied_with Orion Systems.",
                ],
            ),
            (
                "2. Origin",
                [
                    "The alliance with Delacroix Group was formed in 2021 and "
                    "has been renewed twice since without amendment.",
                    "Aurelia Holdings founded_by J. Whitlock.",
                ],
            ),
        ],
    ),
    (
        "08-corvale-analytics-membership.pdf",
        "Corvale Analytics - membership summary",
        "Internal",
        "Membership Secretariat",
        "23 May",
        [
            (
                "1. Member organisations",
                [
                    "Five organisations hold current membership. Membership "
                    "carries no ownership rights and confers no reporting "
                    "obligation on any member.",
                    "Corvale Analytics member_of Delacroix Group.",
                    "Corvale Analytics member_of Aurelia Holdings.",
                    "Corvale Analytics member_of Meridian Group.",
                    "Corvale Analytics member_of Orion Systems.",
                    "Corvale Analytics member_of Northstar Holdings.",
                ],
            ),
            (
                "2. Exports",
                [
                    "Two export corridors are licensed. The licences are "
                    "current and were renewed in April.",
                    "Corvale Analytics licenses_to Delacroix Group.",
                    "Corvale Analytics exports_to Halden Ports.",
                ],
            ),
        ],
    ),
    (
        "09-harrowgate-office-note.pdf",
        "Delacroix Group - head office note",
        "Internal",
        "Facilities",
        "30 May",
        [
            (
                "1. Registration",
                [
                    "The registered head office differs from the administrative "
                    "office. Both are recorded here because the distinction "
                    "matters for two filings that are due in the same week.",
                    "Delacroix Group headquartered_in Harrowgate.",
                    "Delacroix Group headquartered_in Brightwater.",
                ],
            ),
        ],
    ),
    (
        "10-facilities-walkthrough.pdf",
        "Site walkthrough - April",
        "Internal",
        "Facilities",
        "30 April",
        [
            (
                "1. Purpose of the visit",
                [
                    "The quarterly walkthrough was completed on schedule. The "
                    "visiting team were shown the loading bays, the goods lift "
                    "and the adjacent car park. Lighting in the east corridor "
                    "remains uneven and a replacement order has been raised.",
                    "The team also reviewed the fire drill record for the "
                    "quarter and were satisfied that the last evacuation "
                    "completed within the target time. The muster point signage "
                    "is to be refreshed before the summer.",
                    "No further action is required from this visit beyond the "
                    "lighting order already raised.",
                ],
            ),
        ],
    ),
]


def build_styles():
    """Document styles.

    Sizes are chosen so a `Subject predicate Object.` sentence never wraps. PDF
    text extraction returns whatever the layout engine emitted, and a wrapped
    triple arrives at the parser as two fragments that match nothing -- a silent
    failure that looks exactly like "the model extracted nothing".
    """
    base = getSampleStyleSheet()
    return {
        "title": ParagraphStyle(
            "PrismTitle",
            parent=base["Title"],
            fontName="Helvetica-Bold",
            fontSize=15,
            leading=19,
            spaceAfter=10,
            alignment=0,
        ),
        "meta": ParagraphStyle(
            "PrismMeta",
            parent=base["Normal"],
            fontName="Helvetica",
            fontSize=9.5,
            leading=13,
            textColor="#444444",
            spaceAfter=2,
        ),
        "heading": ParagraphStyle(
            "PrismHeading",
            parent=base["Heading2"],
            fontName="Helvetica-Bold",
            fontSize=11.5,
            leading=15,
            spaceBefore=12,
            spaceAfter=5,
            alignment=0,
        ),
        "body": ParagraphStyle(
            "PrismBody",
            parent=base["BodyText"],
            fontName="Helvetica",
            fontSize=10,
            leading=14.5,
            alignment=TA_JUSTIFY,
            spaceAfter=7,
        ),
        "triple": ParagraphStyle(
            "PrismTriple",
            parent=base["BodyText"],
            fontName="Helvetica",
            fontSize=10,
            leading=14.5,
            # Indented so a machine-readable line is visually distinct from
            # prose in the rendered document too, not only after extraction.
            leftIndent=10,
            spaceAfter=3,
        ),
    }


def render(memo, out_dir: Path) -> Path:
    filename, title, classification, author, date, sections = memo
    path = out_dir / filename
    styles = build_styles()

    doc = SimpleDocTemplate(
        str(path),
        pagesize=A4,
        leftMargin=22 * mm,
        rightMargin=22 * mm,
        topMargin=20 * mm,
        bottomMargin=20 * mm,
        title=title,
        author=author,
        subject=classification,
    )

    story: list = [
        Paragraph(title, styles["title"]),
        Paragraph(f"Classification: {classification}", styles["meta"]),
        Paragraph(f"Author: {author}", styles["meta"]),
        Paragraph(f"Date: {date}", styles["meta"]),
        Spacer(1, 6),
    ]

    for heading, paragraphs in sections:
        story.append(Paragraph(heading, styles["heading"]))
        for para in paragraphs:
            # A bare triple gets the indented treatment; prose is justified.
            style = (
                styles["triple"]
                if para.rstrip().endswith(".")
                and para.count(" ") <= 8
                and "_" in para
                else styles["body"]
            )
            story.append(Paragraph(para, style))

    # A page footer carries the classification on every page. A memo printed and
    # separated from its cover should still say what it is.
    def footer(canvas, document):
        canvas.saveState()
        canvas.setFont("Helvetica", 8)
        canvas.setFillColor("#666666")
        canvas.drawString(22 * mm, 12 * mm, f"{classification} - {filename}")
        canvas.drawRightString(A4[0] - 22 * mm, 12 * mm, f"Page {document.page}")
        canvas.restoreState()

    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    default_out = Path(__file__).resolve().parent.parent / "demo" / "pdf-corpus"
    parser.add_argument("--out", type=Path, default=default_out)
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    written = 0
    for memo in MEMOS:
        path = render(memo, args.out)
        written += 1
        print(f"  {path.name}  {path.stat().st_size:,} bytes")

    print(f"\n{written} PDFs written to {args.out}")
    print(
        "\nExpected, once every proposal is approved and the contradiction scan\n"
        "has run. These are predictions, not measurements - the run that reports\n"
        "the real numbers is the one to believe:\n"
        "  conflicts    reports_to, parent_organization, subsidiary_of (firm)\n"
        "               headquartered_in, chief_executive (temporal: depends on\n"
        "               effective times, so observe rather than assume)\n"
        "  violation    sources_from asserted 4 times against a BOUNDED max of 3\n"
        "  must NOT be  invests_in x4, collaborates_with x3, controls x3,\n"
        "               operates_in x3, member_of x5, supplies-style MULTI rows\n"
        "  at the bound operated_by x2 and regulated_by x4 are at the limit,\n"
        "               which is allowed; only exceeding it is a violation\n"
        "  silence      10-facilities-walkthrough.pdf yields no triples at all"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())