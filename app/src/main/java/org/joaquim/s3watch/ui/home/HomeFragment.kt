package org.joaquim.s3watch.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import org.joaquim.s3watch.R // Import R for string resources
import org.joaquim.s3watch.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by viewModels()

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
                HomeViewModel.ConnectionStatus.CONNECTED -> getString(R.string.status_connected)
                HomeViewModel.ConnectionStatus.DISCONNECTED -> getString(R.string.status_disconnected)
                HomeViewModel.ConnectionStatus.CONNECTING -> getString(R.string.status_connecting)
                HomeViewModel.ConnectionStatus.ERROR -> getString(R.string.status_error)
                else -> getString(R.string.status_unknown) // Should not happen
            }
            binding.textConnectionStatus.text = getString(R.string.home_connection_status_prefix, statusString)
        }

        // Set up button listeners
        binding.buttonReconnect.setOnClickListener {
            homeViewModel.attemptDeviceReconnect()
        }

        binding.buttonSendDatetime.setOnClickListener {
            homeViewModel.sendDateTimeToDevice()
        }

        return root
    }

    override fun onResume() {
        super.onResume()
        // Refresh the connected device status when the fragment resumes
        homeViewModel.refreshConnectedDevice()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
