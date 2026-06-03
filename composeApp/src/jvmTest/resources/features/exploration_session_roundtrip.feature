Feature: Persist exploration sessions to disk
  Exploration sessions must round-trip to JSON so that they can be
  reopened later and used to drive automated test scripts.

  Scenario: Saving and reloading a session keeps the graph intact
    Given a session targeting "com.example.app" with 2 states and 1 transition
    When I save it to a temporary directory
    And I read it back from disk
    Then the reloaded session has 2 states and 1 transition
    And the action label of the only transition is "Button#sign_in \"Sign In\""
