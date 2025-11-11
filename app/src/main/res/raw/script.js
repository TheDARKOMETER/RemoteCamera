const img = document.getElementById("mjpegStream");
const canvas = document.getElementById("canvas");
const ctx = canvas.getContext("2d");
const recordBtn = document.getElementById("recordBtn");
const stopBtn = document.getElementById("stopBtn");
const startCamBtn = document.getElementById("startCamBtn");
const stopCamBtn = document.getElementById("stopCamBtn");

let recorder;
let recordedChunks = [];
let stop = false;
let status = "idle";
let master = true;
function startRecording() {
    stop = false;
    recordBtn.disabled = true;
    stopBtn.disabled = false;
    // capture canvas stream
    const stream = canvas.captureStream(30); 
    recorder = new MediaRecorder(stream, { mimeType: "video/webm; codecs=vp8" });

    recorder.ondataavailable = function (e) {
        if (e.data.size > 0) recordedChunks.push(e.data);
    };

    recorder.onstop = function () {
        const blob = new Blob(recordedChunks, { type: "video/webm" });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'recording.webm';
        a.click();
        recordedChunks = [];
    };


    function draw() {
        if (stop) return;
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        console.log("Drawing frame to canvas");
        requestAnimationFrame(draw); // continuously call itself matching screen refresh rate
    }

    setInterval(draw, 33); // approx 30 FPS
    recorder.start();
}

function stopRecording() {
    stop = true;
    recordBtn.disabled = false;
    stopBtn.disabled = true;
    if (recorder && recorder.state === "recording") {
        recorder.stop();
    }
}

function toggleStream() {
    startCamBtn.disabled = !startCamBtn.disabled;
    stopCamBtn.disabled = !stopCamBtn.disabled;
    fetch("/toggleStream", { method: "GET" })
        .then(response => response.text())
        .then(data => {
            console.log("Stream toggled:", data);
        })
        .catch(error => {
            console.error("Error toggling stream:", error);
        });
}