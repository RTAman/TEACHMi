-- B keeps only C's verified report identity/revision/evidence version, never a synthetic assessment.
ALTER TABLE teachmi.learning_session ADD COLUMN assessment_report_snapshot TEXT;
