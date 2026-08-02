package cc.miaooo.prodcallstats.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Builds the "Prod Stats" tool window anchored at the bottom of IDEA. One
 * instance per project; the panel itself implements [com.intellij.openapi.Disposable]
 * so the message-bus subscriptions it opens are torn down when the tool
 * window is closed.
 */
class ClassStatsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ClassStatsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", /* isLockable */ false)
        toolWindow.contentManager.addContent(content)
        Disposer.register(toolWindow.disposable, panel)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
