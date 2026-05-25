package com.asksakis.freegate.ui.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.asksakis.freegate.R

/**
 * Hero row at the top of Settings → About: large brand logo, "PHYLAX" wordmark,
 * and a version subtitle. Replaces the standard preference row layout entirely
 * via android:layout, so the usual title/icon/summary slots are unused.
 */
class AboutHeroPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : Preference(context, attrs, defStyleAttr) {

    private var versionLabel: String = ""

    init {
        layoutResource = R.layout.pref_about_hero
        isSelectable = false
        isIconSpaceReserved = false
    }

    fun setVersionLabel(label: String) {
        if (versionLabel == label) return
        versionLabel = label
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView
            .findViewById<TextView>(R.id.pref_about_version)
            ?.text = versionLabel
    }
}
