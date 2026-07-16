package xx.biketracker.history

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Top-bar-to-History commands. The Today / Collapse-all / Records buttons live in the activity's
 * top bar while the tree state lives in HistoryScreen, so taps are forwarded through this
 * process-wide flow (same pattern as MapSelection).
 */
object HistoryCommands {
    enum class Command { OPEN_TODAY, COLLAPSE_ALL, SHOW_RECORDS }

    val commands = MutableSharedFlow<Command>(extraBufferCapacity = 1)

    fun send(command: Command) {
        commands.tryEmit(command)
    }
}
