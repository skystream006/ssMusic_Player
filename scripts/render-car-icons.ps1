$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$res = Join-Path $PSScriptRoot '..\app\src\main\res'
$output = Join-Path $res 'mipmap-anydpi-v26'
$artwork = Join-Path $res 'drawable-nodpi'
New-Item -ItemType Directory -Force $output | Out-Null

$variants = @('default') + @('midnight', 'royal_purple', 'gold', 'green', 'pink', 'black').ForEach({
    "${_}_light"
    "${_}_dark"
})
foreach ($variant in $variants) {
    $sourceName = if ($variant -eq 'default') { 'ic_launcher' } else { "ic_launcher_$variant" }
    $source = [System.Drawing.Bitmap]::FromFile((Join-Path $res "mipmap-nodpi\$sourceName.png"))
    try {
        # Remove the rounded-square rim; the circular SS/note emblem fills the car's mask.
        $crop = [System.Drawing.Rectangle]::new(
            [int]($source.Width * 0.1), [int]($source.Height * 0.1),
            [int]($source.Width * 0.8), [int]($source.Height * 0.8))
        $image = $source.Clone($crop, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        try {
            $image.Save((Join-Path $artwork "ic_car_$variant.png"), [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $image.Dispose() }
        $background = if ($variant.EndsWith('_light')) { '#f6f8f6' } else { '#080a10' }
        $xml = @"
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background><color android:color="$background" /></background>
    <foreground>
        <inset android:inset="16.6667%">
            <bitmap android:src="@drawable/ic_car_$variant" android:gravity="fill" />
        </inset>
    </foreground>
</adaptive-icon>
"@
        [System.IO.File]::WriteAllText((Join-Path $output "ic_car_$variant.xml"), $xml + "`n")
    } finally { $source.Dispose() }
}
