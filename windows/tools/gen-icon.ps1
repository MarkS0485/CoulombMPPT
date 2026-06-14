# Generates a branded multi-size app icon (Assets/app.ico) without any external
# tooling — draws a teal rounded square with a white sun, renders it at several
# sizes, and packs the PNGs into a Vista-style PNG ICO. Run from anywhere:
#   powershell -ExecutionPolicy Bypass -File tools/gen-icon.ps1
Add-Type -AssemblyName System.Drawing

$root    = Split-Path -Parent $PSScriptRoot
$assets  = Join-Path $root "src\CoulombMppt\Assets"
$outPath = Join-Path $assets "app.ico"
New-Item -ItemType Directory -Force -Path $assets | Out-Null

$sizes = 16,24,32,48,64,128,256
$pngs  = @()

foreach ($s in $sizes) {
    $bmp = New-Object System.Drawing.Bitmap($s, $s, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g   = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    # Rounded-square background in brand teal (#0F766E).
    $teal  = [System.Drawing.Color]::FromArgb(255, 15, 118, 110)
    $brush = New-Object System.Drawing.SolidBrush($teal)
    $r     = [Math]::Max(2, [int]($s * 0.20))
    $d     = $r * 2
    $path  = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc(0, 0, $d, $d, 180, 90)
    $path.AddArc($s - $d, 0, $d, $d, 270, 90)
    $path.AddArc($s - $d, $s - $d, $d, $d, 0, 90)
    $path.AddArc(0, $s - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    $g.FillPath($brush, $path)

    # White sun: centre disc + 8 rays.
    $white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $cx = $s / 2.0; $cy = $s / 2.0
    $cr = $s * 0.16
    $g.FillEllipse($white, [single]($cx - $cr), [single]($cy - $cr), [single]($cr * 2), [single]($cr * 2))

    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::White, [single]([Math]::Max(1.0, $s * 0.055)))
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap   = [System.Drawing.Drawing2D.LineCap]::Round
    $r1 = $s * 0.28; $r2 = $s * 0.40
    for ($i = 0; $i -lt 8; $i++) {
        $a  = $i * [Math]::PI / 4.0
        $x1 = $cx + [Math]::Cos($a) * $r1; $y1 = $cy + [Math]::Sin($a) * $r1
        $x2 = $cx + [Math]::Cos($a) * $r2; $y2 = $cy + [Math]::Sin($a) * $r2
        $g.DrawLine($pen, [single]$x1, [single]$y1, [single]$x2, [single]$y2)
    }

    $g.Dispose()
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngs += ,($ms.ToArray())
    $bmp.Dispose()
}

# Assemble the ICO container (ICONDIR + ICONDIRENTRY[] + PNG payloads).
$out = New-Object System.IO.MemoryStream
$bw  = New-Object System.IO.BinaryWriter($out)
$bw.Write([uint16]0)            # reserved
$bw.Write([uint16]1)            # type = icon
$bw.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s   = $sizes[$i]
    $len = $pngs[$i].Length
    $dim = if ($s -ge 256) { 0 } else { $s }   # 0 means 256 in ICO
    $bw.Write([byte]$dim)       # width
    $bw.Write([byte]$dim)       # height
    $bw.Write([byte]0)          # palette count
    $bw.Write([byte]0)          # reserved
    $bw.Write([uint16]1)        # colour planes
    $bw.Write([uint16]32)       # bits per pixel
    $bw.Write([uint32]$len)     # bytes in resource
    $bw.Write([uint32]$offset)  # offset
    $offset += $len
}
foreach ($p in $pngs) { $bw.Write($p) }
$bw.Flush()
[System.IO.File]::WriteAllBytes($outPath, $out.ToArray())
$bw.Dispose()

Write-Output "Wrote $outPath ($((Get-Item $outPath).Length) bytes, sizes: $($sizes -join ','))"
