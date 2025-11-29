package org.fractalizer.engine;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.*;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * OpenCL engine for GPU-accelerated fractal computation.
 * Handles device initialization, kernel compilation, and memory management.
 */
public class OpenCLEngine implements AutoCloseable {

    public enum DevicePreference {
        AUTO,
        GPU_ONLY,
        CPU_ONLY
    }

    private long platform;
    private long device;
    private long context;
    private long commandQueue;
    private final Map<String, Long> programs = new HashMap<>();
    private final Map<String, Long> kernels = new HashMap<>();
    private final DevicePreference preference;
    private final int preferredDeviceIndex;

    private CLContextCallback contextCallback;
    private String deviceName;
    private String deviceType;
    private long maxWorkGroupSize;
    private long maxMemAllocSize;
    private List<DeviceInfo> availableDevices = Collections.emptyList();

    public OpenCLEngine() {
        this(DevicePreference.AUTO, 0);
    }

    public OpenCLEngine(DevicePreference preference) {
        this(preference, 0);
    }

    public OpenCLEngine(DevicePreference preference, int preferredDeviceIndex) {
        this.preference = preference;
        this.preferredDeviceIndex = Math.max(0, preferredDeviceIndex);
        initialize();
    }

    private void initialize() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer numPlatforms = stack.mallocInt(1);
            checkCLError(clGetPlatformIDs(null, numPlatforms));

            if (numPlatforms.get(0) == 0) {
                throw new RuntimeException("No OpenCL platforms found");
            }

            PointerBuffer platforms = stack.mallocPointer(numPlatforms.get(0));
            checkCLError(clGetPlatformIDs(platforms, (IntBuffer) null));

            availableDevices = enumerateDevices(platforms);
            logAvailableDevices(availableDevices);

            DeviceSelection selection = selectDevice(availableDevices);
            platform = selection.platform();
            device = selection.device();
            deviceType = selection.type();

            // Get device info
            deviceName = selection.name();
            String vendor = selection.vendor();

            PointerBuffer sizeBuffer = stack.mallocPointer(1);
            clGetDeviceInfo(device, CL_DEVICE_MAX_WORK_GROUP_SIZE, sizeBuffer, null);
            maxWorkGroupSize = sizeBuffer.get(0);

            clGetDeviceInfo(device, CL_DEVICE_MAX_MEM_ALLOC_SIZE, sizeBuffer, null);
            maxMemAllocSize = sizeBuffer.get(0);

            System.out.println("OpenCL Device: " + deviceName + " (" + vendor + ")");
            System.out.println("Max Work Group Size: " + maxWorkGroupSize);
            System.out.println("Max Memory Allocation: " + (maxMemAllocSize / 1024 / 1024) + " MB");

            // Create context
            contextCallback = CLContextCallback.create((errinfo, private_info, cb, user_data) -> {
                System.err.println("OpenCL Context Error: " + memUTF8(errinfo));
            });

            PointerBuffer contextProps = stack.mallocPointer(3);
            contextProps.put(0, CL_CONTEXT_PLATFORM);
            contextProps.put(1, platform);
            contextProps.put(2, 0);

            IntBuffer errBuffer = stack.mallocInt(1);
            context = clCreateContext(contextProps, device, contextCallback, NULL, errBuffer);
            checkCLError(errBuffer.get(0));

            // Create command queue
            commandQueue = clCreateCommandQueue(context, device, 0, errBuffer);
            checkCLError(errBuffer.get(0));
        }
    }

    /**
     * Load and compile an OpenCL kernel from resources.
     */
    public void loadKernel(String name, String resourcePath, String functionName) throws IOException {
        String source = loadResource(resourcePath);
        compileKernel(name, source, functionName);
    }

    /**
     * Load and compile an OpenCL kernel from multiple resource files (concatenated).
     * This allows modular kernel organization.
     */
    public void loadKernelFromSources(String name, String functionName, String... resourcePaths) throws IOException {
        StringBuilder source = new StringBuilder();
        for (String path : resourcePaths) {
            source.append("// ============ ").append(path).append(" ============\n");
            source.append(loadResource(path));
            source.append("\n\n");
        }
        compileKernel(name, source.toString(), functionName);
    }

    /**
     * Compile kernel from source string.
     */
    private void compileKernel(String name, String source, String functionName) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer errBuffer = stack.mallocInt(1);

            long program = clCreateProgramWithSource(context, source, errBuffer);
            checkCLError(errBuffer.get(0));

            int buildErr = clBuildProgram(program, device, "", null, NULL);
            if (buildErr != CL_SUCCESS) {
                String buildLog = getProgramBuildInfo(program, device);
                throw new RuntimeException("OpenCL build error on device '" + deviceName + "':\n" + buildLog);
            }

            long kernel = clCreateKernel(program, functionName, errBuffer);
            checkCLError(errBuffer.get(0));

            programs.put(name, program);
            kernels.put(name, kernel);
        }
    }

    /**
     * Create a buffer on the GPU.
     */
    public long createBuffer(long flags, long size) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer errBuffer = stack.mallocInt(1);
            long buffer = clCreateBuffer(context, flags, size, errBuffer);
            checkCLError(errBuffer.get(0));
            return buffer;
        }
    }

    /**
     * Create a buffer initialized with data.
     */
    public long createBuffer(long flags, ByteBuffer data) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer errBuffer = stack.mallocInt(1);
            long buffer = clCreateBuffer(context, flags, data, errBuffer);
            checkCLError(errBuffer.get(0));
            return buffer;
        }
    }

    /**
     * Write data to a GPU buffer.
     */
    public void writeBuffer(long buffer, ByteBuffer data) {
        checkCLError(clEnqueueWriteBuffer(commandQueue, buffer, true, 0, data, null, null));
    }

    /**
     * Read data from a GPU buffer.
     */
    public void readBuffer(long buffer, ByteBuffer data) {
        checkCLError(clEnqueueReadBuffer(commandQueue, buffer, true, 0, data, null, null));
    }

    /**
     * Set kernel argument (buffer).
     */
    public void setKernelArg(String kernelName, int index, long buffer) {
        long kernel = kernels.get(kernelName);
        try (MemoryStack stack = stackPush()) {
            checkCLError(clSetKernelArg(kernel, index, stack.pointers(buffer)));
        }
    }

    /**
     * Set kernel argument (int).
     */
    public void setKernelArgInt(String kernelName, int index, int value) {
        long kernel = kernels.get(kernelName);
        try (MemoryStack stack = stackPush()) {
            checkCLError(clSetKernelArg(kernel, index, stack.ints(value)));
        }
    }

    /**
     * Set kernel argument (float).
     */
    public void setKernelArgFloat(String kernelName, int index, float value) {
        long kernel = kernels.get(kernelName);
        try (MemoryStack stack = stackPush()) {
            checkCLError(clSetKernelArg(kernel, index, stack.floats(value)));
        }
    }

    /**
     * Set kernel argument (float array/vector).
     */
    public void setKernelArgFloats(String kernelName, int index, float... values) {
        long kernel = kernels.get(kernelName);
        try (MemoryStack stack = stackPush()) {
            checkCLError(clSetKernelArg(kernel, index, stack.floats(values)));
        }
    }

    /**
     * Execute a kernel with 2D work size (for image rendering).
     */
    public void executeKernel2D(String kernelName, int width, int height) {
        long kernel = kernels.get(kernelName);

        try (MemoryStack stack = stackPush()) {
            PointerBuffer globalWorkSize = stack.mallocPointer(2);
            globalWorkSize.put(0, width);
            globalWorkSize.put(1, height);

            // Calculate optimal local work size
            int localX = 16;
            int localY = 16;
            while (localX * localY > maxWorkGroupSize) {
                if (localX > localY) localX /= 2;
                else localY /= 2;
            }

            PointerBuffer localWorkSize = stack.mallocPointer(2);
            localWorkSize.put(0, localX);
            localWorkSize.put(1, localY);

            checkCLError(clEnqueueNDRangeKernel(commandQueue, kernel, 2,
                null, globalWorkSize, localWorkSize, null, null));
        }
    }

    /**
     * Wait for all queued commands to complete.
     */
    public void finish() {
        checkCLError(clFinish(commandQueue));
    }

    /**
     * Release a buffer.
     */
    public void releaseBuffer(long buffer) {
        clReleaseMemObject(buffer);
    }

    public long getDevice() {
        return device;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getDeviceType() {
        return deviceType;
    }

    /**
     * Human-readable list of detected devices (type + vendor + name).
     */
    public List<String> getAvailableDeviceSummaries() {
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < availableDevices.size(); i++) {
            DeviceInfo d = availableDevices.get(i);
            summaries.add("[" + i + "] " + d.type() + " - " + d.vendor() + " - " + d.name());
        }
        return Collections.unmodifiableList(summaries);
    }

    public List<DeviceDescriptor> getAvailableDeviceDescriptors() {
        List<DeviceDescriptor> descriptors = new ArrayList<>();
        for (int i = 0; i < availableDevices.size(); i++) {
            DeviceInfo d = availableDevices.get(i);
            descriptors.add(new DeviceDescriptor(i, d.type(), d.vendor(), d.name()));
        }
        return Collections.unmodifiableList(descriptors);
    }

    public long getMaxWorkGroupSize() {
        return maxWorkGroupSize;
    }

    public long getMaxMemAllocSize() {
        return maxMemAllocSize;
    }

    @Override
    public void close() {
        for (long kernel : kernels.values()) {
            clReleaseKernel(kernel);
        }
        for (long program : programs.values()) {
            clReleaseProgram(program);
        }
        clReleaseCommandQueue(commandQueue);
        clReleaseContext(context);
        if (contextCallback != null) {
            contextCallback.free();
        }
    }

    private String loadResource(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String getDeviceInfoString(long device, int param) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer sizeBuffer = stack.mallocPointer(1);
            clGetDeviceInfo(device, param, (ByteBuffer) null, sizeBuffer);

            ByteBuffer buffer = stack.malloc((int) sizeBuffer.get(0));
            clGetDeviceInfo(device, param, buffer, null);

            return memUTF8(buffer, (int) sizeBuffer.get(0) - 1);
        }
    }

    private String getProgramBuildInfo(long program, long device) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer sizeBuffer = stack.mallocPointer(1);
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, sizeBuffer);

            ByteBuffer buffer = stack.malloc((int) sizeBuffer.get(0));
            clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, buffer, null);

            return memUTF8(buffer, (int) sizeBuffer.get(0) - 1);
        }
    }

    private void checkCLError(int error) {
        if (error != CL_SUCCESS) {
            throw new RuntimeException("OpenCL error: " + error);
        }
    }

    private DeviceSelection selectDevice(List<DeviceInfo> devices) {
        if (devices.isEmpty()) {
            throw new RuntimeException("No OpenCL devices found");
        }

        List<DeviceInfo> gpuDevices = devices.stream()
            .filter(d -> "GPU".equalsIgnoreCase(d.type()))
            .toList();
        List<DeviceInfo> cpuDevices = devices.stream()
            .filter(d -> "CPU".equalsIgnoreCase(d.type()))
            .toList();

        DeviceInfo chosen = null;

        if (preference == DevicePreference.CPU_ONLY) {
            chosen = pickByIndex(cpuDevices);
            if (chosen == null) {
                throw new RuntimeException("CPU device requested but none found");
            }
        } else if (preference == DevicePreference.GPU_ONLY) {
            chosen = pickByIndex(gpuDevices);
            if (chosen == null) {
                throw new RuntimeException("GPU device requested but none found");
            }
        } else { // AUTO
            chosen = pickByIndex(gpuDevices);
            if (chosen == null) {
                chosen = pickByIndex(cpuDevices);
            }
            if (chosen == null && !devices.isEmpty()) {
                chosen = devices.get(0);
            }
        }

        if (chosen == null) {
            throw new RuntimeException("No suitable OpenCL GPU or CPU device found");
        }
        System.out.println("Selected " + chosen.type() + " device [" + chosen.vendor() + "]: " + chosen.name());
        return new DeviceSelection(chosen.platform(), chosen.device(), chosen.name(), chosen.vendor(), chosen.type());
    }

    private DeviceInfo pickByIndex(List<DeviceInfo> devices) {
        if (devices.isEmpty()) {
            return null;
        }
        int idx = Math.min(preferredDeviceIndex, devices.size() - 1);
        return devices.get(idx);
    }

    private List<DeviceInfo> enumerateDevices(PointerBuffer platforms) {
        List<DeviceInfo> devices = new ArrayList<>();

        for (int i = 0; i < platforms.capacity(); i++) {
            long plat = platforms.get(i);
            try (MemoryStack stack = stackPush()) {
                IntBuffer numDevices = stack.mallocInt(1);
                int err = clGetDeviceIDs(plat, CL_DEVICE_TYPE_ALL, null, numDevices);
                if (err != CL_SUCCESS || numDevices.get(0) == 0) {
                    continue;
                }

                PointerBuffer devBuf = stack.mallocPointer(numDevices.get(0));
                checkCLError(clGetDeviceIDs(plat, CL_DEVICE_TYPE_ALL, devBuf, (IntBuffer) null));

                for (int d = 0; d < numDevices.get(0); d++) {
                    long dev = devBuf.get(d);
                    String name = getDeviceInfoString(dev, CL_DEVICE_NAME);
                    String vendor = getDeviceInfoString(dev, CL_DEVICE_VENDOR);
                    String type = getDeviceTypeString(dev);
                    devices.add(new DeviceInfo(plat, dev, name, vendor, type));
                }
            }
        }
        return devices;
    }

    private void logAvailableDevices(List<DeviceInfo> devices) {
        if (devices.isEmpty()) {
            System.out.println("No OpenCL devices detected.");
            return;
        }
        System.out.println("Available OpenCL devices:");
        for (int i = 0; i < devices.size(); i++) {
            DeviceInfo d = devices.get(i);
            System.out.println(" [" + i + "] " + d.type() + " - " + d.vendor() + " - " + d.name());
        }
    }

    private String getDeviceTypeString(long deviceId) {
        try (MemoryStack stack = stackPush()) {
            var typeBuf = stack.mallocLong(1);
            clGetDeviceInfo(deviceId, CL_DEVICE_TYPE, typeBuf, null);
            long type = typeBuf.get(0);
            if ((type & CL_DEVICE_TYPE_GPU) != 0) return "GPU";
            if ((type & CL_DEVICE_TYPE_CPU) != 0) return "CPU";
            if ((type & CL_DEVICE_TYPE_ACCELERATOR) != 0) return "Accelerator";
            return "Other";
        }
    }

    public record DeviceDescriptor(int index, String type, String vendor, String name) {}
    private record DeviceInfo(long platform, long device, String name, String vendor, String type) {}
    private record DeviceSelection(long platform, long device, String name, String vendor, String type) {}
}
