package com.spake.brony.doubaoassistant.ui.binding

import android.widget.ImageView
import android.widget.FrameLayout
import android.view.View
import androidx.databinding.BindingAdapter
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.model.ConversationContent
import com.spake.brony.doubaoassistant.data.model.ConversationPhase
import com.spake.brony.doubaoassistant.ui.widget.ConversationView
import com.spake.brony.doubaoassistant.ui.widget.VoiceMarkView

@BindingAdapter("voicePhase", "voiceLevel")
fun bindVoicePhase(view: VoiceMarkView, phase: ConversationPhase, level: Float) { view.setState(phase, level) }

@BindingAdapter("conversationContent", "showCaptions", "conversationDebugMode")
fun bindConversation(view: ConversationView, content: ConversationContent, captions: Boolean, debugMode: Boolean) {
    view.setConversation(content, captions, debugMode)
}

@BindingAdapter("microphoneEnabled")
fun bindMicrophone(view: ImageView, enabled: Boolean) {
    view.setImageResource(if (enabled) R.drawable.ic_voice_mic else R.drawable.ic_voice_muted)
    view.contentDescription = view.context.getString(if (enabled) R.string.voice_disable_mic else R.string.voice_enable_mic)
}

@BindingAdapter("chatPaused")
fun bindChatControl(view: ImageView, paused: Boolean) {
    view.setImageResource(if (paused) R.drawable.ic_voice_play else R.drawable.ic_voice_pause)
    view.contentDescription = view.context.getString(if (paused) R.string.voice_resume_chat else R.string.voice_pause_chat)
}

@BindingAdapter("frameGravity")
fun bindFrameGravity(view: View, gravity: Int) {
    val params = view.layoutParams as FrameLayout.LayoutParams
    params.gravity = gravity
    view.layoutParams = params
}

@BindingAdapter("conversationControlsLeft")
fun bindConversationSide(view: ConversationView, left: Boolean) {
    view.setControlsOnLeft(left)
}
