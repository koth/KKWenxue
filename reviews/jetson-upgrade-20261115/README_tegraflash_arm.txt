Current status:
For arm_tegraflash.tbz2:
tegrabct_v2, tegrahost_v2 are not working on Jetson
These binaries should work on a native arm32 bit platform

For aarch64_tegraflash.tbz2:
tegraopenssl is not working.

===================================================================================================
To generate the flash image on an x86 machine and then flash it onto a Jetson device (or an aarch64/arm host) to flash to another Jetson device (recommended):

Ensure the AArch64/ARM host can support RNDIS, possesses an EXT filesystem to store the BSP, and supports NFS and SSH.

1. On an x86 machine, generate the images you want to flash by running the command as specified in the public documentation:
        sudo ./tools/kernel_flash/l4t_initrd_flash.sh --no-flash --network usb0 <board> <rootdev>

2. Apply the tarball as an overlay to Linux_for_Tegra:
        cd <folder>
        sudo tar xpfv arm_tegraflash.tbz2
        (or sudo tar xpfv aarch64_tegraflash.tbz2

3. Move the entire Linux_for_Tegra folder onto a Jetson device or an AArch64/ARM32 host.
4. Run the following command on an AArch64/ARM32 host:
        cd Linux_for_Tegra/
        sudo ./tools/l4t_flash_prerequisites.sh
        sudo ./tools/kernel_flash/l4t_initrd_flash.sh --flash-only --network usb0

====================================================================================================
To generate an image and flash from a Jetson device (or an AArch64 host) to another Jetson
device (slow)

The steps below use qemu-i386-static to run i386 binaries through binfmt_misc.
For non-Jetson AArch64 hosts, ensure it can install qemu-user-static or run qemu-i386-static.


1. To extract aarch64_tegraflash.tbz2, run the following commands:
        mkdir temp
        cd temp
        tar xpfv aarch64_tegraflash.tbz2
2. To flash, run the following commands:
        cd Linux_for_tegra
        # Replace only tegrarcm_v2 and tegraparser_v2 with the arm/aarch64 version. All other binaries
        # can run with qemu-user-static installed (qemu emulation on aarch64)
        cp <path_to_temp>/temp/Linux_for_Tegra/bootloader/tegrarcm_v2 bootloader
        cp <path_to_temp>/temp/Linux_for_Tegra/bootloader/tegraparser_v2 bootloader
        sudo ./tools/l4t_flash_prerequisites.sh
        # Use flash.sh or l4t_initrd_flash.sh command of your choice
        sudo ./tools/kernel_flash/l4t_initrd_flash.sh --network usb0 <board> <rootdev>
        sudo ./flash.sh <board> <rootdev>

====================================================================================================
To generate an image and flash from an ARM32 host to another Jetson (slow)

If you use l4t_initrd_flash.sh, the ARM32 host must support RNDIS, have an EXT4 filesystem to
contain the BSP, and support NFS and SSH.

If you use flash.sh, this ARM32 host should have an EXT4 filesystem to contain the BSP.

1. To flash, run the following commands:
        sudo tar xpfv arm_tegraflash.tbz2
        cd Linux_for_tegra
        sudo ./tools/l4t_flash_prerequisites.sh
        # Use flash.sh or l4t_initrd_flash.sh command of your choice
        sudo ./tools/kernel_flash/l4t_initrd_flash.sh --network usb0 <board> <rootdev>
        sudo ./flash.sh <board> <rootdev>
