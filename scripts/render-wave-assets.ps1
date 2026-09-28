param([string]$OutputDirectory = (Join-Path $PSScriptRoot '../app/src/main/res/drawable-nodpi'))

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing.Common, System.Drawing.Primitives, System.Private.Windows.GdiPlus, System.Private.Windows.Core -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public static class WaveArtworkRenderer
{
    private static double Curve(double position, double[] knots)
    {
        double scaled = Math.Clamp(position, 0, 0.999999) * (knots.Length - 1);
        int index = (int)scaled;
        double fraction = scaled - index;
        double before = knots[Math.Max(0, index - 1)];
        double start = knots[index];
        double end = knots[index + 1];
        double after = knots[Math.Min(knots.Length - 1, index + 2)];
        return 0.5 * ((2 * start) + (-before + end) * fraction
            + (2 * before - 5 * start + 4 * end - after) * fraction * fraction
            + (-before + 3 * start - 3 * end + after) * fraction * fraction * fraction);
    }

    private static double Bell(double value, double center, double spread)
    {
        double distance = (value - center) / spread;
        return Math.Exp(-distance * distance);
    }

    private static void Ribbon(double horizontal, double vertical, double[] upper, double[] lower,
        double brightness, double phase, ref double red, ref double green, ref double blue)
    {
        double top = Curve(horizontal, upper);
        double bottom = Curve(horizontal, lower);
        double thickness = Math.Max(0.008, bottom - top);
        double across = (vertical - top) / thickness;
        if (across < -0.1 || across > 1.1) return;
        double light = 0.48 + 0.52 * Math.Pow(Math.Sin(horizontal * Math.PI * 1.6 + phase), 2);
        double topEdge = Bell(across, 0.006, 0.008 / thickness);
        double bottomEdge = Bell(across, 0.992, 0.004 / thickness);
        if (across < 0 || across > 1)
        {
            double glow = (topEdge * 0.04 + bottomEdge * 0.16) * brightness * light;
            green += glow * 70;
            blue += glow * 130;
            return;
        }
        double crest = 0.76 + 0.13 * Math.Sin(horizontal * Math.PI * 2.1 + phase);
        double reflection = Bell(across, crest, 0.21) * light;
        double highlight = Bell(across, crest + 0.10, 0.038) * light;
        double broad = Bell(across, 0.3, 0.35) * (0.5 + 0.5 * Math.Cos(horizontal * 8 + phase));
        double rim = topEdge * 0.4 + bottomEdge * 0.85;
        double shade = 0.16 + 0.46 * Math.Pow(Math.Sin(across * Math.PI), 2) + broad * 0.27;
        double fine = 0.5 + 0.5 * Math.Sin((across + 0.07 * Math.Sin(horizontal * 9)) * 39);
        double reflectionRed = 2 + shade * 12 + reflection * 24 + highlight * 38 + rim * 70;
        double reflectionGreen = 9 + shade * 65 + reflection * 120 + highlight * 120 + rim * 145;
        double reflectionBlue = 26 + shade * 220 + reflection * 180 + highlight * 80 + rim * 145;
        double edgeAlpha = Math.Min(1, Math.Min(across, 1 - across) * thickness * 1500);
        double opacity = Math.Clamp(edgeAlpha, 0, 1);
        red = red * (1 - opacity) + reflectionRed * brightness * opacity;
        green = green * (1 - opacity) + reflectionGreen * brightness * opacity;
        blue = blue * (1 - opacity) + (reflectionBlue + fine * reflection * 3) * brightness * opacity;
    }

    public static void Render(string path, int width, int height, bool square)
    {
        double[][] upper = {
            new double[] { .78, .81, .68, .69, .82, .85, .71 },
            new double[] { .13, .25, .38, .31, .39, .36, .10 },
            new double[] { .51, .51, .44, .30, .53, .61, .33 },
            new double[] { .66, .76, .49, .30, .61, .67, .20 }
        };
        double[][] lower = {
            new double[] { 1.13, 1.02, .81, .93, 1.03, 1.04, .93 },
            new double[] { .53, .48, .47, .59, .72, .67, .47 },
            new double[] { .79, .76, .62, .33, .74, .83, .58 },
            new double[] { .89, .85, .58, .32, .73, .80, .59 }
        };
        double[] lights = { .2, .65, .73, 1.12 };
        using Bitmap image = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        BitmapData data = image.LockBits(new Rectangle(0, 0, width, height), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
        byte[] pixels = new byte[data.Stride * height];
        for (int row = 0; row < height; row++)
        {
            for (int column = 0; column < width; column++)
            {
                double horizontal = column / (double)(width - 1);
                double vertical = row / (double)(height - 1);
                if (square) { horizontal = .12 + horizontal * .82; vertical = -.06 + vertical * 1.12; }
                double red = 3, green = 5, blue = 8;
                for (int ribbon = 0; ribbon < upper.Length; ribbon++)
                    Ribbon(horizontal, vertical, upper[ribbon], lower[ribbon], lights[ribbon], ribbon * .53,
                        ref red, ref green, ref blue);
                double grain = ((column * 73 + row * 179) % 31) / 31.0 - .5;
                int offset = row * data.Stride + column * 4;
                pixels[offset] = (byte)Math.Clamp(blue + grain, 0, 255);
                pixels[offset + 1] = (byte)Math.Clamp(green + grain, 0, 255);
                pixels[offset + 2] = (byte)Math.Clamp(red + grain, 0, 255);
                pixels[offset + 3] = 255;
            }
        }
        Marshal.Copy(pixels, 0, data.Scan0, pixels.Length);
        image.UnlockBits(data);
        image.Save(path, ImageFormat.Png);
    }
}
'@

$output = [System.IO.Directory]::CreateDirectory($OutputDirectory).FullName
[WaveArtworkRenderer]::Render((Join-Path $output 'wave_ribbon.png'), 1920, 960, $false)
[WaveArtworkRenderer]::Render((Join-Path $output 'wave_cover.png'), 960, 960, $true)
Get-ChildItem $output -Filter 'wave_*.png' | Select-Object Name, Length