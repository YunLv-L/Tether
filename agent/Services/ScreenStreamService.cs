using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;

namespace TetherAgent.Services;

public class ScreenStreamService
{
    private const int SCREEN_PORT = 5557;
    private TcpListener? _listener;
    private bool _isRunning = false;
    private int _quality = 85;

    public void Start()
    {
        _isRunning = true;
        _listener = new TcpListener(System.Net.IPAddress.Any, SCREEN_PORT);
        _listener.Start();

        Task.Run(() =>
        {
            while (_isRunning)
            {
                try
                {
                    var client = _listener.AcceptTcpClient();
                    Task.Run(() => HandleClient(client));
                }
                catch { }
            }
        });
    }

    public void Stop()
    {
        _isRunning = false;
        _listener?.Stop();
    }

    public void SetQuality(int quality)
    {
        _quality = Math.Clamp(quality, 30, 95);
    }

    private void HandleClient(TcpClient client)
    {
        try
        {
            using (client)
            using (var stream = client.GetStream())
            {
                client.NoDelay = true;

                // 发送分辨率信息
                var bounds = Screen.PrimaryScreen.Bounds;
                int width = bounds.Width;
                int height = bounds.Height;

                string sizeInfo = $"{width}|{height}\n";
                byte[] sizeData = System.Text.Encoding.UTF8.GetBytes(sizeInfo);
                stream.Write(sizeData, 0, sizeData.Length);
                stream.Flush();

                var stopwatch = System.Diagnostics.Stopwatch.StartNew();
                int frameInterval = 1000 / 30;

                while (_isRunning && client.Connected)
                {
                    if (stopwatch.ElapsedMilliseconds < frameInterval)
                    {
                        int remaining = (int)(frameInterval - stopwatch.ElapsedMilliseconds);
                        if (remaining > 0) Thread.Sleep(Math.Min(remaining, 50));
                        continue;
                    }
                    stopwatch.Restart();

                    var imageData = CaptureScreen();
                    if (imageData == null) continue;

                    byte[] lengthBytes = BitConverter.GetBytes(imageData.Length);
                    stream.Write(lengthBytes, 0, 4);
                    stream.Write(imageData, 0, imageData.Length);
                    stream.Flush();
                }
            }
        }
        catch { }
    }

    private byte[]? CaptureScreen()
    {
        try
        {
            var screen = Screen.PrimaryScreen;
            using var bitmap = new Bitmap(screen.Bounds.Width, screen.Bounds.Height);
            using var g = Graphics.FromImage(bitmap);
            g.CopyFromScreen(screen.Bounds.X, screen.Bounds.Y, 0, 0, screen.Bounds.Size);

            using var ms = new MemoryStream();
            var codec = ImageCodecInfo.GetImageEncoders()
                .FirstOrDefault(c => c.FormatID == ImageFormat.Jpeg.Guid);

            if (codec != null)
            {
                var encoderParams = new EncoderParameters(1);
                encoderParams.Param[0] = new EncoderParameter(
                    System.Drawing.Imaging.Encoder.Quality,
                    (long)_quality
                );
                bitmap.Save(ms, codec, encoderParams);
            }
            else
            {
                bitmap.Save(ms, ImageFormat.Png);
            }

            return ms.ToArray();
        }
        catch { return null; }
    }
}