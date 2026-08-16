param(
    [string]$HashPath = ''
)

# Hash-only helper for the Windows DNS preflight. It must never request console input.
if (-not $HashPath) {
    [Console]::Error.WriteLine('HashPath is required.')
    exit 2
}

[Console]::Out.WriteLine((Get-FileHash -LiteralPath $HashPath -Algorithm SHA256).Hash.ToLowerInvariant())
exit 0
