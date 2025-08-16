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

        // Observe connectedDeviceAddress to update the UI
        homeViewModel.connectedDeviceAddress.observe(viewLifecycleOwner) { address ->
            if (address != null) {
                binding.textHome.text = getString(R.string.home_connected_to, address)
            } else {
                binding.textHome.text = getString(R.string.home_no_device_connected)
            }
        }
        // Remove the observer for homeViewModel.text if it's no longer used or managed differently
        // homeViewModel.text.observe(viewLifecycleOwner) {
        //    binding.textHome.text = it
        // }

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
