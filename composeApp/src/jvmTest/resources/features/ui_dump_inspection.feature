Feature: Inspect UI hierarchy dumps from UIAutomator
  In order to navigate an Android screen in the desktop app,
  the tool must parse UIAutomator dumps and answer questions about the tree.

  Background:
    Given the dump "sample_dump.xml" is loaded

  Scenario: Counting nodes in the parsed tree
    Then the tree has 7 nodes
    And the root package is "com.example.app"

  Scenario: Hit-testing returns the smallest node at a coordinate
    When I pick the element under the point 500, 880
    Then the selected element has resource id "com.example.app:id/sign_in"

  Scenario: Hit-testing outside any element returns nothing
    When I pick the element under the point 5000, 5000
    Then nothing is selected

  Scenario: Only enabled clickable elements are exposed as actions
    When I collect clickable actions for package "com.example.app" with a limit of 100
    Then 2 actions are available
    And every action targets a node whose resource id ends with "sign_in" or "register"

  Scenario: Action list honours the maximum cap
    When I collect clickable actions for package "com.example.app" with a limit of 1
    Then 1 actions are available
