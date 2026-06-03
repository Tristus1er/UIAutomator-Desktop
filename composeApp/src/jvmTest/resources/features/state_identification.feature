Feature: Detect identical application states during exploration
  To keep the exploration graph compact, states whose visible copy only
  differs on dynamic noise (clocks, list items without resource-ids) must
  share the same fingerprint. But wizard-style screens with identical
  layouts and different labelled text — the copy of TextViews and Buttons
  with resource-ids — must count as distinct states, otherwise exploration
  stops at the first screen.

  Scenario: Noise on anonymous nodes does not shift the fingerprint
    Given the dump "anon_base.xml" is loaded as "A"
    And the dump "anon_base_text_changed.xml" is loaded as "B"
    Then "A" and "B" share the same fingerprint

  Scenario: Different labelled text produces a distinct fingerprint
    Given the dump "sample_dump.xml" is loaded as "A"
    And the dump "sample_dump_text_changed.xml" is loaded as "B"
    Then "A" and "B" have different fingerprints

  Scenario: An additional clickable produces a new fingerprint
    Given the dump "sample_dump.xml" is loaded as "A"
    And the dump "sample_dump_extra_button.xml" is loaded as "B"
    Then "A" and "B" have different fingerprints
