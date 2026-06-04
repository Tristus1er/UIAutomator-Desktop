package com.salaun.tristan.uiautomator.i18n

/**
 * All user-visible strings of the application. One instance per [Language] is
 * built in [Translations]. Fields that contain `%s` / `%d` placeholders are
 * formatted with [String.format] at the call site.
 *
 * Field names are grouped by the screen that introduced them so that adding a
 * new string stays local to the touched UI. Log strings emitted by the
 * [com.salaun.tristan.uiautomator.explorer.Explorer] stay in English because
 * they contain technical content (state ids, coordinates, package names) and
 * are meant for debugging rather than for the end-user.
 */
data class Strings(
    val code: String,

    // -- Common ---------------------------------------------------------------
    val back: String,
    val save: String,
    val cancel: String,
    val delete: String,
    val refresh: String,
    val open: String,
    val browse: String,
    val importLabel: String,
    val exportLabel: String,
    val home: String,
    val copy: String,

    // -- Toolbar / MainScreen -------------------------------------------------
    val toolbarCapture: String,
    val toolbarRefreshDevices: String,
    val toolbarNoDevice: String,
    val toolbarChooseDevice: String,
    val toolbarExplorer: String,
    val toolbarSessions: String,
    val toolbarGraph: String,
    val toolbarSettings: String,
    val toolbarRules: String,

    // -- Screenshot panel -----------------------------------------------------
    val screenshotHint: String,

    // -- Capture flow (AppState) ----------------------------------------------
    val captureErrorAdbMissing: String,
    val captureErrorNoDevice: String,
    val captureStatusInProgress: String,
    val captureStatusDumping: String,
    val captureStatusOkFmt: String, // "Capture OK (%1$d KB, %2$d chars)"
    val captureErrorFmt: String, // "Error: %s"

    // -- Capture actions menu (MainScreen toolbar) ---------------------------
    val captureActionsMenu: String,
    val captureActionExport: String,
    val captureActionImport: String,
    val captureActionCopyImage: String,
    val captureActionCopyXml: String,
    val captureActionSaveImage: String,
    val captureActionSaveXml: String,
    val captureExportDialogTitle: String,
    val captureImportDialogTitle: String,
    val captureSaveImageDialogTitle: String,
    val captureSaveXmlDialogTitle: String,
    val captureCopiedImage: String,
    val captureCopiedXml: String,
    val captureSavedFmt: String, // "Saved: %s"
    val captureExportedFmt: String, // "Capture exported: %s"
    val captureImportedFmt: String, // "Capture imported: %s"
    val captureActionFailedFmt: String, // "Operation failed: %s"
    val captureNoneHint: String, // Shown when an action is triggered without an active capture

    // -- XML tree panel -------------------------------------------------------
    val treeEmpty: String,
    val treeSelectNodeHint: String,
    val treeExpandAll: String,
    val treeCollapseAll: String,

    // -- Settings screen ------------------------------------------------------
    val settingsTitle: String,
    val settingsAdbPathHint: String,
    val settingsAdbPathLabel: String,
    val settingsAutoDetect: String,
    val settingsStatusPrefix: String,
    val settingsPickAdbTitle: String,
    val settingsAdbAutoDetecting: String,
    val settingsAdbNotFound: String,
    val settingsAdbDetected: String, // "ADB detected: %s"
    val settingsAdbChecking: String,
    val settingsAdbOk: String, // "OK — %s"
    val settingsAdbFail: String, // "Failed: %s"
    val settingsAdbNotConfigured: String,
    val settingsLanguage: String,
    val settingsLanguageSystem: String, // "System default"
    val settingsLanguageSystemWith: String, // "System default (%s)"

    // -- Explorer screen ------------------------------------------------------
    val explorerTitle: String,
    val explorerViewGraph: String,
    val explorerStart: String,
    val explorerStop: String,
    val explorerTargetPackage: String,
    val explorerFromCapture: String,
    val explorerMaxStates: String,
    val explorerMaxDepth: String,
    val explorerMaxActions: String,
    val explorerDelay: String,
    val explorerLog: String,
    val explorerCopyLog: String,
    val explorerAutoScroll: String,
    val explorerSummary: String,
    val explorerNoSession: String,
    val explorerPackagePrefix: String,
    val explorerStatesPrefix: String,
    val explorerTransitionsPrefix: String,
    val explorerDirectoryPrefix: String,
    val explorerProgressFmt: String, // "States: %1$d • Actions: %2$d/%3$d"
    val explorerProgressState: String, // " • %s"
    val explorerProgressAction: String, // " → %s"
    val explorerErrorAdbMissing: String,
    val explorerErrorNoPackage: String,
    val explorerCancelled: String,
    val explorerErrorFmt: String, // "Exploration: %s"

    // -- Manual exploration screen --------------------------------------------
    val manualTitle: String,
    val manualToolbarLabel: String,
    val manualStart: String,
    val manualEnd: String,
    val manualBack: String,
    val manualHome: String,
    val manualRefresh: String,
    val manualEmptyHint: String,
    val manualClickHint: String,
    val manualCurrentStatePrefix: String, // "Current state: %s"
    val manualLogTitle: String,
    val manualScrollCapture: String,       // button label
    val manualScrollExit: String,          // button label, exits stitched view
    val manualScrollHint: String,          // hint shown above the stitched view
    val manualScrollBadgeFmt: String,      // "Scroll capture · %1$d frames · %2$d px"

    // -- Graph screen ---------------------------------------------------------
    val graphTitle: String,
    val graphHeaderFmt: String, // "%1$d states · %2$d transitions"
    val graphNoSession: String,
    val graphSelectState: String,
    val graphFingerprintPrefix: String,
    val graphDepthPrefix: String,
    val graphCaptureUnavailable: String,
    val graphEnlargeCapture: String,
    val graphOutgoingTransitions: String,
    val graphFit: String,
    val graphReorganize: String,
    val graphTargetError: String,
    val graphTargetLeftApp: String,
    val graphBulletDash: String,
    val graphDeleteOneFmt: String,           // "🗑  Delete state %s"
    val graphDeleteManyFmt: String,          // "🗑  Delete %d selected states"
    val graphMergeFmt: String,               // "⥥  Merge %d selected states into %s"
    val graphDeleteConfirmTitle: String,
    val graphDeleteConfirmBodyFmt: String,   // "Delete %s? %d transition(s) will be removed."
    val graphExportHtml: String,             // toolbar button label
    val graphExportHtmlDialogTitle: String,
    val graphHtmlExportedFmt: String,        // "Graph exported as HTML: %s"
    val graphHtmlExportFailedFmt: String,    // "HTML export failed: %s"

    // -- Sessions screen ------------------------------------------------------
    val sessionsTitle: String,
    val sessionsFolderPrefix: String,
    val sessionsNone: String,
    val sessionsImportDialogTitle: String,
    val sessionsExportDialogTitle: String,
    val sessionsDeleteConfirmTitle: String,
    val sessionsDeleteConfirmBodyFmt: String, // "%s" → absolute path
    val sessionsUnknownPackage: String,
    val sessionsStatesTransitionsFmt: String, // "%1$d states · %2$d transitions"
    val sessionsRefresh: String,
    val sessionsImportFailedFmt: String, // "Import failed: %s"
    val sessionsExportFailedFmt: String, // "Export failed: %s"
    val sessionsExportedFmt: String, // "Session exported: %s"
    val sessionsImportedFmt: String, // "Session imported: %s"
    val sessionsDeletedFmt: String, // "Session deleted: %s"
    val sessionsDeleteOutside: String, // refusal message
    val sessionsDeleteFailedFmt: String, // "Unable to delete %s"
    val sessionsSessionUnreadableFmt: String, // "Session unreadable: %s"
    val sessionsFolderCopyHint: String,         // tooltip on the folder path
    val sessionsFolderCopiedToast: String,      // shown after the path lands on the clipboard

    // -- Screenshot detail window --------------------------------------------
    val detailClickablesCountFmt: String, // "%d clickable elements"
    val detailShowClickables: String,
    val detailPackageUnknown: String,
    val detailDestination: String,
    val detailDestinationHint: String,
    val detailBoundsFmt: String, // "bounds [%1$d,%2$d][%3$d,%4$d]"
    val detailDestNotTested: String,
    val detailDestErrorFmt: String, // "Error: %s"
    val detailDestLeftApp: String,
    val detailDestLoopFmt: String, // "↺ Loop on the same state (%s)."
    val detailDestGoFmt: String, // "→ %s"
    val detailOpenState: String,

    // -- Rules: package list screen ------------------------------------------
    val rulesTitle: String,
    val rulesNone: String,
    val rulesPackageStatsFmt: String, // "%1$d rules · %2$d enabled · %3$d actions"
    val rulesNewPackage: String,
    val rulesNewPackageHint: String,
    val rulesImportDialogTitle: String,
    val rulesExportDialogTitle: String,
    val rulesImportedFmt: String, // "Rules imported: %s"
    val rulesImportFailedFmt: String, // "Rule import failed: %s"
    val rulesExportedFmt: String, // "Rules exported: %s"
    val rulesExportFailedFmt: String, // "Rule export failed: %s"
    val rulesDeletePackageConfirmTitle: String,
    val rulesDeletePackageConfirmBodyFmt: String, // "%s"

    // -- Rules: package detail screen ----------------------------------------
    val rulePackageTitleFmt: String, // "Rules · %s"
    val ruleAddScreen: String,
    val rulePackageNoRules: String,
    val ruleEnabledLabel: String,
    val ruleActionsCountFmt: String, // "%d actions"
    val ruleMoveUp: String,
    val ruleMoveDown: String,
    val ruleDeleteConfirmTitle: String,
    val ruleDeleteConfirmBodyFmt: String, // "%s"

    // -- Rules: editor screen -------------------------------------------------
    val ruleEditTitleNew: String,
    val ruleEditTitleEdit: String,
    val ruleEditPickHint: String,
    val ruleNameLabel: String,
    val ruleCaptureButton: String,
    val ruleSignatureSection: String,
    val ruleSignaturePickHint: String,
    val ruleSignatureEmpty: String,
    val ruleSignatureUseRootId: String,
    val ruleSignatureAddResourceId: String,
    val ruleSignatureAddText: String,
    val ruleSignatureAddContentDesc: String,
    val ruleSignatureClear: String,
    val ruleRoutineSection: String,
    val ruleRoutineEmpty: String,
    val ruleAddAction: String,
    val ruleActionClick: String,
    val ruleActionTypeText: String,
    val ruleActionScroll: String,
    val ruleActionWait: String,
    val ruleActionBack: String,
    val ruleActionCapture: String,
    val ruleSelectNodeFirst: String,
    val ruleSelectorBy: String,
    val ruleSelectorValue: String,
    val ruleSelectorByResourceId: String,
    val ruleSelectorByContentDesc: String,
    val ruleSelectorByText: String,
    val ruleSelectorMatchContains: String,
    val ruleScrollDirection: String,
    val ruleScrollDirUp: String,
    val ruleScrollDirDown: String,
    val ruleScrollDirLeft: String,
    val ruleScrollDirRight: String,
    val ruleScrollAmount: String,
    val ruleScrollAmountItems: String,
    val ruleScrollAmountPercent: String,
    val ruleScrollAmountPixels: String,
    val ruleScrollAmountToEnd: String,
    val ruleScrollValue: String,
    val ruleTypeTextValue: String,
    val ruleWaitMs: String,
    val ruleRemoveAction: String,
    val ruleSaveDisabledHint: String,
)
