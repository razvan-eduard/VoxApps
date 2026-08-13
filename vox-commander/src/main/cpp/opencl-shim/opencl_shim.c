// The OpenCL import shim: every CL entry point ggml uses, forwarded through dlopen at runtime.
//
// Linking the real loader put "libOpenCL.so" into DT_NEEDED, and a hard DT_NEEDED is all-or-
// nothing: on a device without the vendor driver the engine library itself refuses to load, which
// killed the default NLU engine outright on every non-Qualcomm device (found by the emulator
// smoke test, whose dlopen failed before a single test ran). This file is what ggml links
// instead — a static archive, so the final .so has no OpenCL dependency at all.
//
// At runtime the first CL call dlopens the vendor driver. Present: every call forwards, and the
// GPU works exactly as before. Absent: clGetPlatformIDs reports zero platforms, which is the
// answer ggml's backend init already handles — device count zero, capacity query null, and the
// probe's NO_GPU_BACKEND outcome instead of a dead library.
//
// Symbols are hidden: each engine carries its own copy, and an exported clFoo from one library
// must never satisfy a lookup from another.

#define CL_TARGET_OPENCL_VERSION 300
#include <CL/cl.h>
#include <dlfcn.h>
#include <pthread.h>

#define VOX_HIDDEN __attribute__((visibility("hidden")))

// Every CL function the ggml OpenCL backend references (the union across llama and whisper,
// taken from the undefined-symbol table of both built libraries). A new upstream call shows up
// as a link error here, which is the visible failure this list wants.
#define VOX_CL_FUNCS(X)            \
    X(clBuildProgram)              \
    X(clCreateBuffer)              \
    X(clCreateBufferWithProperties)\
    X(clCreateCommandQueue)        \
    X(clCreateContext)             \
    X(clCreateImage)               \
    X(clCreateKernel)              \
    X(clCreateProgramWithBinary)   \
    X(clCreateProgramWithSource)   \
    X(clCreateSubBuffer)           \
    X(clEnqueueBarrierWithWaitList)\
    X(clEnqueueCopyBuffer)         \
    X(clEnqueueFillBuffer)         \
    X(clEnqueueMarkerWithWaitList) \
    X(clEnqueueNDRangeKernel)      \
    X(clEnqueueReadBuffer)         \
    X(clEnqueueWriteBuffer)        \
    X(clFinish)                    \
    X(clFlush)                     \
    X(clGetDeviceIDs)              \
    X(clGetDeviceInfo)             \
    X(clGetKernelWorkGroupInfo)    \
    X(clGetPlatformIDs)            \
    X(clGetPlatformInfo)           \
    X(clGetProgramBuildInfo)       \
    X(clGetProgramInfo)            \
    X(clReleaseEvent)              \
    X(clReleaseKernel)             \
    X(clReleaseMemObject)          \
    X(clReleaseProgram)            \
    X(clSetKernelArg)              \
    X(clWaitForEvents)

#define VOX_DECLARE(name) static __typeof__(name) *p_##name;
VOX_CL_FUNCS(VOX_DECLARE)
#undef VOX_DECLARE

static void *vox_cl_lib;
static pthread_once_t vox_cl_once = PTHREAD_ONCE_INIT;

static void vox_cl_load(void) {
    // The plain soname resolves the vendor driver through the app's linker namespace — that is
    // what the manifest's uses-native-library declaration exists for. The absolute path is a
    // fallback for devices that ship the driver without listing it.
    vox_cl_lib = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!vox_cl_lib) vox_cl_lib = dlopen("/vendor/lib64/libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!vox_cl_lib) return;
#define VOX_RESOLVE(name) p_##name = (__typeof__(name) *) dlsym(vox_cl_lib, #name);
    VOX_CL_FUNCS(VOX_RESOLVE)
#undef VOX_RESOLVE
}

static int vox_cl_ready(void) {
    pthread_once(&vox_cl_once, vox_cl_load);
    return vox_cl_lib != NULL;
}

// clGetPlatformIDs is the graceful exit: zero platforms is a legitimate CL answer that every
// consumer already handles, so a missing driver reports exactly that instead of an error.
VOX_HIDDEN cl_int clGetPlatformIDs(cl_uint num_entries, cl_platform_id *platforms, cl_uint *num_platforms) {
    if (!vox_cl_ready() || !p_clGetPlatformIDs) {
        if (num_platforms) *num_platforms = 0;
        return CL_SUCCESS;
    }
    return p_clGetPlatformIDs(num_entries, platforms, num_platforms);
}

// Everything below is only reachable once a platform exists, so a missing pointer is a hard
// error, answered with the CL code for an impossible operation rather than a crash.
#define VOX_GUARD(name) if (!vox_cl_ready() || !p_##name)

VOX_HIDDEN cl_int clGetPlatformInfo(cl_platform_id platform, cl_platform_info param_name, size_t param_value_size, void *param_value, size_t *param_value_size_ret) {
    VOX_GUARD(clGetPlatformInfo) return CL_INVALID_OPERATION;
    return p_clGetPlatformInfo(platform, param_name, param_value_size, param_value, param_value_size_ret);
}

VOX_HIDDEN cl_int clGetDeviceIDs(cl_platform_id platform, cl_device_type device_type, cl_uint num_entries, cl_device_id *devices, cl_uint *num_devices) {
    VOX_GUARD(clGetDeviceIDs) { if (num_devices) *num_devices = 0; return CL_DEVICE_NOT_FOUND; }
    return p_clGetDeviceIDs(platform, device_type, num_entries, devices, num_devices);
}

VOX_HIDDEN cl_int clGetDeviceInfo(cl_device_id device, cl_device_info param_name, size_t param_value_size, void *param_value, size_t *param_value_size_ret) {
    VOX_GUARD(clGetDeviceInfo) return CL_INVALID_OPERATION;
    return p_clGetDeviceInfo(device, param_name, param_value_size, param_value, param_value_size_ret);
}

VOX_HIDDEN cl_context clCreateContext(const cl_context_properties *properties, cl_uint num_devices, const cl_device_id *devices, void (CL_CALLBACK *pfn_notify)(const char *, const void *, size_t, void *), void *user_data, cl_int *errcode_ret) {
    VOX_GUARD(clCreateContext) { if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION; return NULL; }
    return p_clCreateContext(properties, num_devices, devices, pfn_notify, user_data, errcode_ret);
}

VOX_HIDDEN cl_command_queue clCreateCommandQueue(cl_context context, cl_device_id device, cl_command_queue_properties properties, cl_int *errcode_ret) {
    VOX_GUARD(clCreateCommandQueue) { if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION; return NULL; }
    return p_clCreateCommandQueue(context, device, properties, errcode_ret);
}

VOX_HIDDEN cl_mem clCreateBuffer(cl_context context, cl_mem_flags flags, size_t size, void *host_ptr, cl_int *errcode_ret) {
    VOX_GUARD(clCreateBuffer) { if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION; return NULL; }
    return p_clCreateBuffer(context, flags, size, host_ptr, errcode_ret);
}

VOX_HIDDEN cl_mem clCreateBufferWithProperties(cl_context context, const cl_mem_properties *properties, cl_mem_flags flags, size_t size, void *host_ptr, cl_int *errcode_ret) {
    VOX_GUARD(clCreateBufferWithProperties) { if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION; return NULL; }
    return p_clCreateBufferWithProperties(context, properties, flags, size, host_ptr, errcode_ret);
}

VOX_HIDDEN cl_mem clCreateSubBuffer(cl_mem buffer, cl_mem_flags flags, cl_buffer_create_type buffer_create_type, const void *buffer_create_info, cl_int *errcode_ret) {
    VOX_GUARD(clCreateSubBuffer) { if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION; return NULL; }
    return p_clCreateSubBuffer(buffer, flags, buffer_create_type, buffer_create_info, errcode_ret);
}

VOX_HIDDEN cl_mem clCreateImage(cl_context context, cl_mem_flags flags, const cl_image_format *image_format, const cl_image_desc *image_desc, void *host_ptr, cl_int *errcode_ret) {
    VOX_GUARD(clCreateImage) { if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION; return NULL; }
    return p_clCreateImage(context, flags, image_format, image_desc, host_ptr, errcode_ret);
}

VOX_HIDDEN cl_program clCreateProgramWithSource(cl_context context, cl_uint count, const char **strings, const size_t *lengths, cl_int *errcode_ret) {
    VOX_GUARD(clCreateProgramWithSource) { if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION; return NULL; }
    return p_clCreateProgramWithSource(context, count, strings, lengths, errcode_ret);
}

VOX_HIDDEN cl_program clCreateProgramWithBinary(cl_context context, cl_uint num_devices, const cl_device_id *device_list, const size_t *lengths, const unsigned char **binaries, cl_int *binary_status, cl_int *errcode_ret) {
    VOX_GUARD(clCreateProgramWithBinary) { if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION; return NULL; }
    return p_clCreateProgramWithBinary(context, num_devices, device_list, lengths, binaries, binary_status, errcode_ret);
}

VOX_HIDDEN cl_int clBuildProgram(cl_program program, cl_uint num_devices, const cl_device_id *device_list, const char *options, void (CL_CALLBACK *pfn_notify)(cl_program, void *), void *user_data) {
    VOX_GUARD(clBuildProgram) return CL_INVALID_OPERATION;
    return p_clBuildProgram(program, num_devices, device_list, options, pfn_notify, user_data);
}

VOX_HIDDEN cl_int clGetProgramBuildInfo(cl_program program, cl_device_id device, cl_program_build_info param_name, size_t param_value_size, void *param_value, size_t *param_value_size_ret) {
    VOX_GUARD(clGetProgramBuildInfo) return CL_INVALID_OPERATION;
    return p_clGetProgramBuildInfo(program, device, param_name, param_value_size, param_value, param_value_size_ret);
}

VOX_HIDDEN cl_int clGetProgramInfo(cl_program program, cl_program_info param_name, size_t param_value_size, void *param_value, size_t *param_value_size_ret) {
    VOX_GUARD(clGetProgramInfo) return CL_INVALID_OPERATION;
    return p_clGetProgramInfo(program, param_name, param_value_size, param_value, param_value_size_ret);
}

VOX_HIDDEN cl_kernel clCreateKernel(cl_program program, const char *kernel_name, cl_int *errcode_ret) {
    VOX_GUARD(clCreateKernel) { if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION; return NULL; }
    return p_clCreateKernel(program, kernel_name, errcode_ret);
}

VOX_HIDDEN cl_int clSetKernelArg(cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void *arg_value) {
    VOX_GUARD(clSetKernelArg) return CL_INVALID_OPERATION;
    return p_clSetKernelArg(kernel, arg_index, arg_size, arg_value);
}

VOX_HIDDEN cl_int clGetKernelWorkGroupInfo(cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name, size_t param_value_size, void *param_value, size_t *param_value_size_ret) {
    VOX_GUARD(clGetKernelWorkGroupInfo) return CL_INVALID_OPERATION;
    return p_clGetKernelWorkGroupInfo(kernel, device, param_name, param_value_size, param_value, param_value_size_ret);
}

VOX_HIDDEN cl_int clEnqueueNDRangeKernel(cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim, const size_t *global_work_offset, const size_t *global_work_size, const size_t *local_work_size, cl_uint num_events_in_wait_list, const cl_event *event_wait_list, cl_event *event) {
    VOX_GUARD(clEnqueueNDRangeKernel) return CL_INVALID_OPERATION;
    return p_clEnqueueNDRangeKernel(command_queue, kernel, work_dim, global_work_offset, global_work_size, local_work_size, num_events_in_wait_list, event_wait_list, event);
}

VOX_HIDDEN cl_int clEnqueueReadBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read, size_t offset, size_t size, void *ptr, cl_uint num_events_in_wait_list, const cl_event *event_wait_list, cl_event *event) {
    VOX_GUARD(clEnqueueReadBuffer) return CL_INVALID_OPERATION;
    return p_clEnqueueReadBuffer(command_queue, buffer, blocking_read, offset, size, ptr, num_events_in_wait_list, event_wait_list, event);
}

VOX_HIDDEN cl_int clEnqueueWriteBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write, size_t offset, size_t size, const void *ptr, cl_uint num_events_in_wait_list, const cl_event *event_wait_list, cl_event *event) {
    VOX_GUARD(clEnqueueWriteBuffer) return CL_INVALID_OPERATION;
    return p_clEnqueueWriteBuffer(command_queue, buffer, blocking_write, offset, size, ptr, num_events_in_wait_list, event_wait_list, event);
}

VOX_HIDDEN cl_int clEnqueueCopyBuffer(cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer, size_t src_offset, size_t dst_offset, size_t size, cl_uint num_events_in_wait_list, const cl_event *event_wait_list, cl_event *event) {
    VOX_GUARD(clEnqueueCopyBuffer) return CL_INVALID_OPERATION;
    return p_clEnqueueCopyBuffer(command_queue, src_buffer, dst_buffer, src_offset, dst_offset, size, num_events_in_wait_list, event_wait_list, event);
}

VOX_HIDDEN cl_int clEnqueueFillBuffer(cl_command_queue command_queue, cl_mem buffer, const void *pattern, size_t pattern_size, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event *event_wait_list, cl_event *event) {
    VOX_GUARD(clEnqueueFillBuffer) return CL_INVALID_OPERATION;
    return p_clEnqueueFillBuffer(command_queue, buffer, pattern, pattern_size, offset, size, num_events_in_wait_list, event_wait_list, event);
}

VOX_HIDDEN cl_int clEnqueueBarrierWithWaitList(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event *event_wait_list, cl_event *event) {
    VOX_GUARD(clEnqueueBarrierWithWaitList) return CL_INVALID_OPERATION;
    return p_clEnqueueBarrierWithWaitList(command_queue, num_events_in_wait_list, event_wait_list, event);
}

VOX_HIDDEN cl_int clEnqueueMarkerWithWaitList(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event *event_wait_list, cl_event *event) {
    VOX_GUARD(clEnqueueMarkerWithWaitList) return CL_INVALID_OPERATION;
    return p_clEnqueueMarkerWithWaitList(command_queue, num_events_in_wait_list, event_wait_list, event);
}

VOX_HIDDEN cl_int clFinish(cl_command_queue command_queue) {
    VOX_GUARD(clFinish) return CL_INVALID_OPERATION;
    return p_clFinish(command_queue);
}

VOX_HIDDEN cl_int clFlush(cl_command_queue command_queue) {
    VOX_GUARD(clFlush) return CL_INVALID_OPERATION;
    return p_clFlush(command_queue);
}

VOX_HIDDEN cl_int clWaitForEvents(cl_uint num_events, const cl_event *event_list) {
    VOX_GUARD(clWaitForEvents) return CL_INVALID_OPERATION;
    return p_clWaitForEvents(num_events, event_list);
}

VOX_HIDDEN cl_int clReleaseEvent(cl_event event) {
    VOX_GUARD(clReleaseEvent) return CL_INVALID_OPERATION;
    return p_clReleaseEvent(event);
}

VOX_HIDDEN cl_int clReleaseKernel(cl_kernel kernel) {
    VOX_GUARD(clReleaseKernel) return CL_INVALID_OPERATION;
    return p_clReleaseKernel(kernel);
}

VOX_HIDDEN cl_int clReleaseMemObject(cl_mem memobj) {
    VOX_GUARD(clReleaseMemObject) return CL_INVALID_OPERATION;
    return p_clReleaseMemObject(memobj);
}

VOX_HIDDEN cl_int clReleaseProgram(cl_program program) {
    VOX_GUARD(clReleaseProgram) return CL_INVALID_OPERATION;
    return p_clReleaseProgram(program);
}
