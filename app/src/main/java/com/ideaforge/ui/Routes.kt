package com.ideaforge.ui

/** Navigation routes, also used as notification deep links (MainActivity.EXTRA_ROUTE). */
object Routes {
    const val HOME = "home"
    const val RECORDING = "recording"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val SESSION = "session/{sessionId}?tab={tab}"
    const val APPROVAL = "approval/{sessionId}"
    const val PLAN_EDIT = "plan/{planId}/edit"
    const val STAGE_EDIT = "session/{sessionId}/stage/{stage}/{round}"
    const val DELIVERABLE = "deliverable/{deliverableId}"
    const val SETTINGS_SECTION = "settings/{section}"
    const val PROMPT_EDIT = "settings/prompts/{agent}"

    const val TAB_AUDIO = 0
    const val TAB_TRANSCRIPT = 1
    const val TAB_IDEAS = 2
    const val TAB_EVALUATION = 3
    const val TAB_PLANS = 4
    const val TAB_DELIVERABLES = 5

    fun session(id: Long, tab: Int = TAB_TRANSCRIPT) = "session/$id?tab=$tab"
    fun approval(sessionId: Long) = "approval/$sessionId"
    fun planEdit(planId: Long) = "plan/$planId/edit"
    fun stageEdit(sessionId: Long, stage: String, round: Int) = "session/$sessionId/stage/$stage/$round"
    fun deliverable(id: Long) = "deliverable/$id"
    fun settingsSection(section: String) = "settings/$section"
    fun promptEdit(agent: String) = "settings/prompts/$agent"
}
