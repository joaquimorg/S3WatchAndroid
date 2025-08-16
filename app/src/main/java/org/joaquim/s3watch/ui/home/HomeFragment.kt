package org.joaquim.s3watch.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
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

        val textView: TextView = binding.textHome
        homeViewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }

        // Observar o endereço do dispositivo conectado separadamente se você quiser mais controle
        // homeViewModel.connectedDeviceAddress.observe(viewLifecycleOwner) { address ->
        //    if (address != null) {
        //        textView.text = "Conectado a: $address"
        //    } else {
        //        textView.text = "Nenhum dispositivo conectado"
        //    }
        // }
        return root
    }

    override fun onResume() {
        super.onResume()
        // Atualizar o dispositivo conectado caso tenha mudado enquanto o fragmento não estava visível
        homeViewModel.refreshConnectedDevice()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
