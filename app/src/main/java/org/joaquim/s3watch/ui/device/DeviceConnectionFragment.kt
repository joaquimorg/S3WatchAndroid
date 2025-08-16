package org.joaquim.s3watch.ui.device

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import org.joaquim.s3watch.R
import org.joaquim.s3watch.databinding.FragmentDeviceConnectionBinding

class DeviceConnectionFragment : Fragment() {

    private var _binding: FragmentDeviceConnectionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DeviceConnectionViewModel by viewModels()
    private lateinit var deviceListAdapter: BluetoothDeviceListAdapter

    private val TAG = DeviceConnectionFragment::class.java.simpleName

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context?.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
    }

    private val requestBluetoothEnable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Log.d(TAG, "Bluetooth ativado pelo usuário.")
                viewModel.startScan() // Tentar scan novamente após ativação
            } else {
                Log.w(TAG, "Bluetooth não foi ativado pelo usuário.")
                Toast.makeText(context, "Bluetooth é necessário para buscar dispositivos.", Toast.LENGTH_LONG).show()
            }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            permissions.entries.forEach {
                if (!it.value) {
                    allGranted = false
                    Log.w(TAG, "Permissão não concedida: ${it.key}")
                }
            }

            if (allGranted) {
                Log.d(TAG, "Todas as permissões concedidas após solicitação.")
                tryToStartScan()
            } else {
                Toast.makeText(context, "Todas as permissões são necessárias para buscar dispositivos.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeviceConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupObservers()

        binding.scanButton.setOnClickListener {
            if (viewModel.isScanning.value == true) {
                viewModel.stopScan()
            } else {
                Log.d(TAG, "Botão Scan clicado, verificando permissões.")
                checkAndRequestPermissions()
            }
        }
    }

    private fun setupRecyclerView() {
        deviceListAdapter = BluetoothDeviceListAdapter { device ->
            viewModel.stopScan() // Parar o scan antes de tentar conectar
            viewModel.connectToDevice(device)
        }
        binding.devicesRecyclerView.apply {
            adapter = deviceListAdapter
            layoutManager = LinearLayoutManager(context)
        }
    }

    private fun setupObservers() {
        viewModel.discoveredDevices.observe(viewLifecycleOwner) { devices ->
            Log.d(TAG, "Discovered devices updated: ${devices.size} devices")
            deviceListAdapter.submitList(devices.toList()) // .toList() to create a new list for DiffUtil
        }

        viewModel.isScanning.observe(viewLifecycleOwner) { isScanning ->
            binding.scanButton.text = if (isScanning) getString(R.string.stop_scan) else getString(R.string.scan_devices)
            //binding.progressBar.visibility = if (isScanning) View.VISIBLE else View.GONE
        }

        viewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            status?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Connection Status: $it")
                // Você pode querer limpar a mensagem após exibi-la
                // viewModel.clearConnectionStatus() // Necessitaria de uma função no VM
            }
        }

        viewModel.navigateToHome.observe(viewLifecycleOwner) {
            Log.i(TAG, "Navegando para Home Screen.")
            findNavController().navigate(R.id.action_deviceConnection_to_home)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // Para Android 12+, ACCESS_FINE_LOCATION é frequentemente necessária para obter resultados de scan BLE
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        } else { // Para APIs < S (Android 11 e inferior)
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "Permissões a serem solicitadas: ${permissionsToRequest.joinToString()}")
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.i(TAG, "Nenhuma permissão nova a ser solicitada. Tentando iniciar o scan.")
            tryToStartScan()
        }
    }

    private fun tryToStartScan() {
        val btAdapter = bluetoothAdapter
        if (btAdapter == null) {
            Toast.makeText(context, "Bluetooth não suportado.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!btAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBluetoothEnable.launch(enableBtIntent)
        } else {
            Log.d(TAG, "Bluetooth está ativado, iniciando scan.")
            viewModel.startScan()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
