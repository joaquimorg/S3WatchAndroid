package org.joaquim.s3watch.ui.home

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
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
            binding.textHome.text = statusText // Main status text (e.g., "Conectado a: S3Watch")
        }

        homeViewModel.connectedDeviceName.observe(viewLifecycleOwner) { name ->
            if (name != null) {
                binding.textDeviceName.text = getString(R.string.home_device_name_prefix, name)
                binding.textDeviceName.visibility = View.VISIBLE
            } else {
                binding.textDeviceName.visibility = View.GONE
            }
        }

        homeViewModel.connectionState.observe(viewLifecycleOwner) { state ->
            val statusString = when(state) {
                BluetoothCentralManager.ConnectionStatus.CONNECTED -> getString(R.string.status_connected)
                BluetoothCentralManager.ConnectionStatus.DISCONNECTED -> getString(R.string.status_disconnected)
                BluetoothCentralManager.ConnectionStatus.CONNECTING -> getString(R.string.status_connecting)
                BluetoothCentralManager.ConnectionStatus.ERROR -> getString(R.string.status_error)
                else -> getString(R.string.status_unknown) // Covers null or any other state
            }
            binding.textConnectionStatus.text = getString(R.string.home_connection_status_prefix, statusString)
        }

        homeViewModel.reconnectButtonText.observe(viewLifecycleOwner) { stringResId ->
            binding.buttonReconnect.setText(stringResId)
        }

        homeViewModel.deviceData.observe(viewLifecycleOwner) { deviceData ->
            deviceData?.let {
                binding.textBattery.text = getString(R.string.battery_level_prefix, it.battery)
                binding.textCharging.text = getString(R.string.charging_status_prefix, it.charging.toString())
                binding.textSteps.text = getString(R.string.steps_count_prefix, it.steps)
            }
        }

        // Set up button listeners
        binding.buttonReconnect.setOnClickListener {
            homeViewModel.handleReconnectButtonClick()
        }

        binding.buttonSendDatetime.setOnClickListener {
            homeViewModel.sendDateTimeToDevice()
        }

        binding.buttonSendStatus.setOnClickListener {
            homeViewModel.sendStatusToDevice()
        }

        return root
    }

    // onResume no longer needs to call refreshConnectedDevice as ViewModel handles this internally.

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
