package dev.cannoli.scorza.input

import android.hardware.input.InputManager
import android.view.InputDevice
import dev.cannoli.scorza.libretro.LibretroInput
import dev.cannoli.scorza.libretro.LibretroRunner

data class ControllerIdentity(
    val descriptor: String,
    val name: String,
    val isInternal: Boolean
)

class ControllerManager(
    private val maxPorts: Int = LibretroRunner.MAX_PORTS,
    private val configStore: ControllerConfigStore? = null,
    private val defaultControls: () -> Map<String, Int> = { emptyMap() }
) : InputManager.InputDeviceListener {

    val slots = arrayOfNulls<ControllerIdentity>(maxPorts)
    val portInputs = Array(maxPorts) { LibretroInput() }
    val portInputMasks = IntArray(maxPorts)
    val portPressedKeys = Array(maxPorts) { mutableSetOf<Int>() }

    private val deviceToPort = mutableMapOf<Int, Int>()
    private val descriptorToPort = mutableMapOf<String, Int>()
    private var hasInternal = false

    var onDeviceDisconnected: ((port: Int) -> Unit)? = null
    var onDeviceConnected: ((port: Int, identity: ControllerIdentity) -> Unit)? = null

    val connectedPortCount: Int get() = slots.count { it != null }

    fun initialize() {
        val ids = InputDevice.getDeviceIds()
        val gameDevices = mutableListOf<Pair<Int, InputDevice>>()
        var foundInternal = false
        for (id in ids) {
            val device = InputDevice.getDevice(id) ?: continue
            if (!isGameController(device)) continue
            gameDevices.add(id to device)
            if (isInternalDevice(device)) foundInternal = true
        }
        if (foundInternal) {
            for ((id, _) in gameDevices) deviceToPort[id] = 0
            slots[0] = ControllerIdentity("builtin", "Built-in Controller", true)
            hasInternal = true
        } else {
            for ((id, _) in gameDevices) assignDevice(id)
        }
    }

    fun assignDevice(deviceId: Int): Int {
        deviceToPort[deviceId]?.let { return it }

        val device = InputDevice.getDevice(deviceId) ?: return -1
        val descriptor = device.descriptor
        val internal = isInternalDevice(device)
        val identity = ControllerIdentity(descriptor, device.name, internal)

        val existingPort = descriptorToPort[descriptor]
        if (existingPort != null) {
            slots[existingPort] = identity
            deviceToPort[deviceId] = existingPort
            loadControlsForPort(existingPort, descriptor)
            onDeviceConnected?.invoke(existingPort, identity)
            return existingPort
        }

        val port: Int
        if (internal) {
            if (slots[0]?.isInternal == true) {
                deviceToPort[deviceId] = 0
                return 0
            }
            if (slots[0] != null) bumpPort0ToNext()
            port = 0
            hasInternal = true
        } else {
            val start = if (hasInternal) 1 else 0
            port = (start until maxPorts).firstOrNull { slots[it] == null } ?: return -1
        }

        slots[port] = identity
        deviceToPort[deviceId] = port
        descriptorToPort[descriptor] = port
        loadControlsForPort(port, descriptor)
        onDeviceConnected?.invoke(port, identity)
        return port
    }

    private fun loadControlsForPort(port: Int, descriptor: String) {
        val portInput = portInputs[port]
        portInput.resetDefaults()
        val controls = configStore?.let {
            if (it.hasConfig(descriptor)) it.readControls(descriptor) else null
        } ?: defaultControls()
        for (btn in portInput.buttons) {
            val keyCode = controls[btn.prefKey] ?: continue
            portInput.assign(btn, keyCode)
        }
    }

    private fun bumpPort0ToNext() {
        val bumped = slots[0] ?: return
        val newPort = (1 until maxPorts).firstOrNull { slots[it] == null } ?: return
        slots[newPort] = bumped
        slots[0] = null
        descriptorToPort[bumped.descriptor] = newPort
        val bumpedDeviceId = deviceToPort.entries.firstOrNull { it.value == 0 }?.key
        if (bumpedDeviceId != null) deviceToPort[bumpedDeviceId] = newPort
    }

    fun removeDevice(deviceId: Int): Int? {
        val port = deviceToPort.remove(deviceId) ?: return null
        portInputMasks[port] = 0
        portPressedKeys[port].clear()
        onDeviceDisconnected?.invoke(port)
        return port
    }

    fun getPortForDeviceId(deviceId: Int): Int? = deviceToPort[deviceId]

    fun resetAllInput() {
        for (p in 0 until maxPorts) {
            portInputMasks[p] = 0
            portPressedKeys[p].clear()
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (!isGameController(device)) return
        assignDevice(deviceId)
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        removeDevice(deviceId)
    }

    override fun onInputDeviceChanged(deviceId: Int) {}

    companion object {
        fun isGameController(device: InputDevice): Boolean {
            val sources = device.sources
            return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
                    sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        }

        fun isInternalDevice(device: InputDevice): Boolean {
            return device.vendorId == 0 && device.productId == 0
        }
    }
}
