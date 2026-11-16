"""Prepare the offline image and preserve list without printing credentials."""
from pathlib import Path
import re
import shutil

upgrade_dir = Path('/home/jetson/workspace/jetpack-upgrade-20261115')
bsp = upgrade_dir / 'r36.4.3/Linux_for_Tegra'
rootfs = bsp / 'rootfs'
base_rootfs = upgrade_dir / 'r35.5.0/Linux_for_Tegra/rootfs'
ota = bsp / 'tools/ota_tools/version_upgrade'

# Recovery utilities must use the base release's glibc. This host is now R35.5.
generator = (ota / 'l4t_generate_ota_package.sh').read_text()
array = re.search(r'BASE_RECOVERY_FILES=\((.*?)\n\)', generator, re.S)
assert array, 'Cannot identify the official base recovery file list'
for source, _ in re.findall(r"'([^']+):([^']+)'", array[1]):
    original = Path('/') / source
    assert original.is_file(), 'Missing required base recovery utility'
    destination = base_rootfs / source
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(original, destination, follow_symlinks=True)

# Restrict the official multi-board payload to the actual P3767-0003 module.
specs_file = ota / 'ota_board_specs.conf'
specs = specs_file.read_text()
array_pattern = r'(jetson_orin_nano_devkit_ota_emmc_r35_r36_spec=\()(.*?)(\n\))'
match = re.search(array_pattern, specs, re.S)
assert match
entries = [line for line in match[2].splitlines()
           if "'boardid=" in line and 'boardsku=0003;' in line]
assert len(entries) == 1
specs = specs[:match.start(2)] + '\n' + entries[0] + specs[match.end(2):]
specs_file.write_text(specs)

# Retain the existing account's hash; never write its plaintext password.
old_user = next(line for line in Path('/etc/passwd').read_text().splitlines()
                if line.startswith('jetson:'))
new_user = next(line for line in (rootfs / 'etc/passwd').read_text().splitlines()
                if line.startswith('jetson:'))
assert old_user.split(':')[2:4] == new_user.split(':')[2:4]
old_shadow = next(line for line in Path('/etc/shadow').read_text().splitlines()
                  if line.startswith('jetson:'))
assert old_shadow.split(':')[1].startswith('$')
shadow_file = rootfs / 'etc/shadow'
shadow = shadow_file.read_text().splitlines()
shadow_file.write_text('\n'.join(old_shadow if line.startswith('jetson:') else line
                                for line in shadow) + '\n')

for source in Path('/etc/ssh').glob('ssh_host_*'):
    if source.is_file():
        shutil.copy2(source, rootfs / 'etc/ssh' / source.name)
network_source = Path('/etc/NetworkManager/system-connections')
network_destination = rootfs / 'etc/NetworkManager/system-connections'
network_destination.mkdir(parents=True, exist_ok=True)
for source in network_source.iterdir():
    if source.is_file():
        shutil.copy2(source, network_destination / source.name)

workspace_lines = [line for line in Path('/etc/fstab').read_text().splitlines()
                   if not line.startswith('#') and '/home/jetson/workspace' in line]
assert len(workspace_lines) == 1
fstab = rootfs / 'etc/fstab'
content = fstab.read_text()
assert '/home/jetson/workspace' not in content
fstab.write_text(content.rstrip() + '\n' + workspace_lines[0] + '\n')
(rootfs / 'home/jetson/workspace').mkdir(parents=True, exist_ok=True)

# Never traverse the workspace mount (which contains the image being built).
# Enumerate only home entries on APP; the separate p15 partition stays untouched.
paths = [entry.relative_to('/').as_posix()
         for entry in sorted(Path('/home/jetson').iterdir())
         if entry.name not in {'workspace', '.cache'}]
assert 'home/jetson/.ssh' in paths
assert not any(re.search(r'\s|#', path) for path in paths)
(ota / 'ota_backup_files_list.txt').write_text('\n'.join(paths) + '\n')
print('Offline account, SSH identity, network, workspace mount and home preserve list prepared.')
