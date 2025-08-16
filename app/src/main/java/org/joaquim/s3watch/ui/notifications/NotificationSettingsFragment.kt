package org.joaquim.s3watch.ui.notifications

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import org.joaquim.s3watch.databinding.FragmentNotificationSettingsBinding

class NotificationSettingsFragment : Fragment() {

    private var _binding: FragmentNotificationSettingsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val notificationSettingsViewModel =
            ViewModelProvider(this).get(NotificationSettingsViewModel::class.java)

        _binding = FragmentNotificationSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // TODO: Update the TextView ID in fragment_notification_settings.xml if necessary
        val textView: TextView = binding.textNotifications
        notificationSettingsViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}