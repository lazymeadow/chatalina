async function handleRegister() {
    const data = JSON.stringify(Object.fromEntries(new FormData(document.getElementById('register'))));
    const response = await fetch("/register", {
        method: "POST",
        body: data,
        headers: {"Content-Type": "application/json"}
    });
    if (!response.ok) {
        const messageElement = document.getElementById('message')
        messageElement.classList.add("error")
        messageElement.innerHTML = await response.text()
    } else {
        window.location = '/login?message=All+signed+up!+I+hope+you+remember+that+password.+Time+to+log+in!'
    }
}