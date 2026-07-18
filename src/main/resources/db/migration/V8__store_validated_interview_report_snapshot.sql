ALTER TABLE interview_report
  ADD COLUMN report_snapshot JSON NULL AFTER score_snapshot;

