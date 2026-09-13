using System;
using System.Runtime.InteropServices;

// Native VIX wrapper for the fresh-run lane. Derived from the pinned session-recovery-vix.cs
// (same P/Invoke surface and fail-closed exit-code handling) with one addition: the guest login can
// request VIX_LOGIN_IN_GUEST_REQUIRE_INTERACTIVE_ENVIRONMENT (0x08) so that programs run in the
// logged-on console session (session 1) instead of session 0. This is what `vmrun -interactive` does.
public sealed class FreshRunVix : IDisposable
{
    private const int InvalidHandle = 0;
    private const int PropertyNone = 0;
    private const int PropertyJobResultHandle = 3010;
    private const int PropertyJobResultGuestProgramExitCode = 3018;
    private const int WorkstationProvider = 3;
    private const int LoginRequireInteractiveEnvironment = 0x08;
    private const int RunProgramActivateWindow = 0x0002;
    private int host = InvalidHandle;
    private int vm = InvalidHandle;
    private bool guestLoggedIn;

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool SetDllDirectory(string path);

    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern int VixHost_Connect(int apiVersion, int hostType, string hostName, int hostPort,
        string userName, string password, int options, int propertyList, IntPtr callback, IntPtr clientData);
    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern void VixHost_Disconnect(int hostHandle);
    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern int VixVM_Open(int hostHandle, string vmxPath, IntPtr callback, IntPtr clientData);
    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern int VixVM_LoginInGuest(int vmHandle, string userName, string password, int options,
        IntPtr callback, IntPtr clientData);
    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern int VixVM_LogoutFromGuest(int vmHandle, IntPtr callback, IntPtr clientData);
    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern int VixVM_RunProgramInGuest(int vmHandle, string program, string arguments, int options,
        int propertyList, IntPtr callback, IntPtr clientData);
    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern int VixVM_CopyFileFromHostToGuest(int vmHandle, string hostPath, string guestPath,
        int options, int propertyList, IntPtr callback, IntPtr clientData);
    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern int VixVM_CopyFileFromGuestToHost(int vmHandle, string guestPath, string hostPath,
        int options, int propertyList, IntPtr callback, IntPtr clientData);
    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern int VixVM_DeleteFileInGuest(int vmHandle, string guestPath, IntPtr callback, IntPtr clientData);
    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl, EntryPoint = "VixJob_Wait")]
    private static extern ulong VixJobWait(int jobHandle, int firstProperty);
    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl, EntryPoint = "VixJob_Wait")]
    private static extern ulong VixJobWaitHandle(int jobHandle, int firstProperty, out int resultHandle, int terminator);
    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl, EntryPoint = "VixJob_Wait")]
    private static extern ulong VixJobWaitGuestProgram(int jobHandle, int firstProperty, out int exitCode, int terminator);
    [DllImport("Vix64AllProductsDyn.dll", CallingConvention = CallingConvention.Cdecl)]
    private static extern void Vix_ReleaseHandle(int handle);

    public static void ConfigureLibraryDirectory(string directory)
    {
        if (!SetDllDirectory(directory)) throw new InvalidOperationException("VIX_DLL_DIRECTORY_FAILED");
    }

    public bool InteractiveLogin { get; private set; }

    public FreshRunVix(string vmxPath, string guestUser, string guestPassword, bool interactiveLogin)
    {
        int job = VixHost_Connect(-1, WorkstationProvider, null, 0, null, null, 0, InvalidHandle, IntPtr.Zero, IntPtr.Zero);
        host = WaitForHandle(job, "VIX_HOST_CONNECT_FAILED");
        job = VixVM_Open(host, vmxPath, IntPtr.Zero, IntPtr.Zero);
        vm = WaitForHandle(job, "VIX_VM_OPEN_FAILED");
        int loginOptions = interactiveLogin ? LoginRequireInteractiveEnvironment : 0;
        job = VixVM_LoginInGuest(vm, guestUser, guestPassword, loginOptions, IntPtr.Zero, IntPtr.Zero);
        Wait(job, interactiveLogin ? "VIX_GUEST_INTERACTIVE_LOGIN_FAILED" : "VIX_GUEST_LOGIN_FAILED");
        guestLoggedIn = true;
        InteractiveLogin = interactiveLogin;
    }

    public void Run(string program, string arguments)
    {
        WaitForGuestProgram(VixVM_RunProgramInGuest(vm, program, arguments, 0, InvalidHandle, IntPtr.Zero, IntPtr.Zero),
            "VIX_GUEST_PROGRAM_FAILED");
    }

    public void RunInteractive(string program, string arguments)
    {
        WaitForGuestProgram(VixVM_RunProgramInGuest(vm, program, arguments, RunProgramActivateWindow, InvalidHandle, IntPtr.Zero, IntPtr.Zero),
            "VIX_INTERACTIVE_GUEST_PROGRAM_FAILED");
    }

    public void CopyToGuest(string hostPath, string guestPath)
    {
        Wait(VixVM_CopyFileFromHostToGuest(vm, hostPath, guestPath, 0, InvalidHandle, IntPtr.Zero, IntPtr.Zero),
            "VIX_COPY_TO_GUEST_FAILED");
    }

    public void CopyFromGuest(string guestPath, string hostPath)
    {
        Wait(VixVM_CopyFileFromGuestToHost(vm, guestPath, hostPath, 0, InvalidHandle, IntPtr.Zero, IntPtr.Zero),
            "VIX_COPY_FROM_GUEST_FAILED");
    }

    public void DeleteGuestFile(string guestPath)
    {
        Wait(VixVM_DeleteFileInGuest(vm, guestPath, IntPtr.Zero, IntPtr.Zero), "VIX_DELETE_GUEST_FILE_FAILED");
    }

    private static int WaitForHandle(int job, string error)
    {
        int result;
        ulong code = VixJobWaitHandle(job, PropertyJobResultHandle, out result, PropertyNone);
        Vix_ReleaseHandle(job);
        if (code != 0 || result == InvalidHandle) throw new InvalidOperationException(error + ":" + code);
        return result;
    }

    private static void Wait(int job, string error)
    {
        ulong code = VixJobWait(job, PropertyNone);
        Vix_ReleaseHandle(job);
        if (code != 0) throw new InvalidOperationException(error + ":" + code);
    }

    private static void WaitForGuestProgram(int job, string error)
    {
        int exitCode;
        ulong code = VixJobWaitGuestProgram(job, PropertyJobResultGuestProgramExitCode, out exitCode, PropertyNone);
        Vix_ReleaseHandle(job);
        if (code != 0) throw new InvalidOperationException(error + ":" + code);
        if (exitCode != 0) throw new InvalidOperationException(error + ":GUEST_EXIT_CODE=" + exitCode);
    }

    public void Dispose()
    {
        if (guestLoggedIn && vm != InvalidHandle)
        {
            try { Wait(VixVM_LogoutFromGuest(vm, IntPtr.Zero, IntPtr.Zero), "VIX_GUEST_LOGOUT_FAILED"); }
            catch { }
        }
        if (vm != InvalidHandle) Vix_ReleaseHandle(vm);
        if (host != InvalidHandle) VixHost_Disconnect(host);
        vm = InvalidHandle;
        host = InvalidHandle;
        guestLoggedIn = false;
    }
}
