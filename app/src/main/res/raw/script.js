const img = document.getElementById("mjpegStream")
const canvas = document.getElementById("canvas")
const ctx = canvas.getContext("2d")
const recordBtn = document.getElementById("recordBtn")
const stopBtn = document.getElementById("stopBtn")
const startCamBtn = document.getElementById("startCamBtn")
const stopCamBtn = document.getElementById("stopCamBtn")
const statusText = document.getElementById("statusText")
let recorder
let recordedChunks = []
let stopped = true
let isStreaming = false

updateUI()

img.src = "/stream" + "?t=" + new Date().getTime() // prevent caching
img.onerror = function(e) {
    console.log("Error loading MJPEG stream", e)
    setTimeout(function() {
        img.src = "/stream" + "?t=" + new Date().getTime()
    }, 1000) // retry after 1 second
}

function fetchStatus() {
    fetch("/streamStatus", { method: "GET" })
        .then(response => response.text())
        .then(data => {
            isStreaming = data === "true"
            updateUI()
        })
        .catch(error => {
            console.error("Error fetching status:", error)
        })
}

fetchStatus()
setInterval(fetchStatus, 500) 


function startRecording() {
    stopped = false
    updateUI()
    // capture canvas stream
    const stream = canvas.captureStream(30)
    recorder = new MediaRecorder(stream, { mimeType: "video/webm; codecs=vp8" })

    recorder.ondataavailable = function (e) {
        if (e.data.size > 0) recordedChunks.push(e.data)
    }

    recorder.onstop = function () {
        const blob = new Blob(recordedChunks, { type: "video/webm" })
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = 'recording.webm'
        a.click()
        recordedChunks = []
    }


    function draw() {
        if (stopped) return
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
        console.log("Drawing frame to canvas")
        requestAnimationFrame(draw) // continuously call itself matching screen refresh rate
    }

    setInterval(draw, 33) // approx 30 FPS
    recorder.start()
}

function stopRecording() {
    stopped = true
    updateUI()
    if (recorder && recorder.state === "recording") {
        recorder.stop()
    }
}

function flashlight(state) {
    fetch(`/flashlight?state=${state}`, { method: "GET" })
        .then(response => response.text())
        .then(data => {
            console.log(data)
        })
        .catch(error => {
            console.error("Error toggling flashlight:", error)
        })
}

function updateUI() {
    // Record button: enabled if not recording
    recordBtn.disabled = !(stopped)

    // stopped button: disabled if recording stopped
    stopBtn.disabled = (stopped)  // or check recorder state if available

    // Status text
    statusText.textContent = isStreaming ? (stopped ? "Streaming" : "Recording") : "Stopped"
}