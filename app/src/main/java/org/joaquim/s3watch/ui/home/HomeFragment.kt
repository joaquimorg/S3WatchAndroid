package org.joaquim.s3watch.ui.home

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.widget.PopupMenu
import androidx.fragment.app.viewModels
import org.joaquim.s3watch.R // Import R for string resources
import org.joaquim.s3watch.bluetooth.BluetoothCentralManager // Added import
import org.joaquim.s3watch.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by viewModels()

    @SuppressLint("StringFormatInvalid")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Observe LiveData from HomeViewModel
        homeViewModel.text.observe(viewLifecycleOwner) { statusText ->
            // Keep for potential subtitle use if needed in future
        }

        homeViewModel.connectedDeviceName.observe(viewLifecycleOwner) { name ->
            binding.titleDeviceName.text = name ?: getString(R.string.home_no_device_connected)
        }

        homeViewModel.connectionState.observe(viewLifecycleOwner) { state ->
            val statusString = when(state) {
                BluetoothCentralManager.ConnectionStatus.CONNECTED -> getString(R.string.status_connected)
                BluetoothCentralManager.ConnectionStatus.DISCONNECTED -> getString(R.string.status_disconnected)
                BluetoothCentralManager.ConnectionStatus.CONNECTING -> getString(R.string.status_connecting)
                BluetoothCentralManager.ConnectionStatus.ERROR -> getString(R.string.status_error)
                else -> getString(R.string.status_unknown)
            }
            binding.subtitleConnection.text = statusString
        }

        // Reconnect label is applied dynamically to the popup menu when it opens

        homeViewModel.deviceData.observe(viewLifecycleOwner) { deviceData ->
            deviceData?.let {
                val batt = "🔋 ${it.battery}%" + if (it.charging) " (charging)" else ""
                binding.metricBattery.text = batt
                binding.metricSteps.text = "👣 ${it.steps}"
            }
        }

        // Overflow menu on the card
        binding.buttonCardMenu.setOnClickListener { v ->
            val popup = PopupMenu(requireContext(), v)
            popup.menuInflater.inflate(R.menu.home_card_menu, popup.menu)
            val reconnectTitle = getString(homeViewModel.reconnectButtonText.value ?: R.string.button_reconnect_text)
            popup.menu.findItem(R.id.action_reconnect).title = reconnectTitle
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_reconnect -> homeViewModel.handleReconnectButtonClick()
                    R.id.action_send_datetime -> homeViewModel.sendDateTimeToDevice()
                    R.id.action_send_status -> homeViewModel.sendStatusToDevice()
                    R.id.action_send_demo_notification -> homeViewModel.sendDemoNotificationToDevice()
                }
                true
            }
            popup.show()
        }

        return root
    }

    // onResume no longer needs to call refreshConnectedDevice as ViewModel handles this internally.

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
