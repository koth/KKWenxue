param([string]$BuildDir = "$PSScriptRoot/build", [string]$Configuration = 'Release')
$ErrorActionPreference = 'Stop'
$TaskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$TorchRoot = Join-Path $TaskRoot '.qwen3-tts-venv/Lib/site-packages/torch'
& cmake -S $PSScriptRoot -B $BuildDir -G 'Visual Studio 17 2022' -A x64 "-DAUDIO8_TORCH_ROOT=$TorchRoot"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& cmake --build $BuildDir --config $Configuration --parallel 2
exit $LASTEXITCODE
