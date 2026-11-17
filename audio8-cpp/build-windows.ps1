param(
  [string]$BuildDir = "$PSScriptRoot/build",
  [string]$Configuration = 'Release',
  [string]$TorchRoot = $env:LIBTORCH_ROOT,
  [string]$CudaArchitectures = '86',
  [string]$Toolset = ''
)
$ErrorActionPreference = 'Stop'
$TaskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $TorchRoot) { $TorchRoot = Join-Path $TaskRoot '.qwen3-tts-venv/Lib/site-packages/torch' }
if (-not (Test-Path -LiteralPath (Join-Path $TorchRoot 'include/ATen/ATen.h'))) {
  throw 'Pass -TorchRoot with a CUDA LibTorch/PyTorch installation, or set LIBTORCH_ROOT.'
}
$CmakeArgs = @('-S', $PSScriptRoot, '-B', $BuildDir, '-G', 'Visual Studio 17 2022', '-A', 'x64',
  "-DAUDIO8_TORCH_ROOT=$TorchRoot", "-DCMAKE_CUDA_ARCHITECTURES=$CudaArchitectures")
if ($Toolset) { $CmakeArgs += @('-T', $Toolset) }
& cmake @CmakeArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& cmake --build $BuildDir --config $Configuration --parallel 2
exit $LASTEXITCODE
