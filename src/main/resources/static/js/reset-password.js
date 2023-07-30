async function handleResetPassword() {
    const data = JSON.stringify(Object.fromEntries(new FormData(document.getElementById('reset-password'))));
    const response = await fetch("/reset-password", {
        method: "POST",
        body: data,
        headers: {"Content-Type": "application/json"}
    });
    if (!response.ok) {
        const messageElement = document.getElementById('message')
        messageElement.classList.add("error")
        messageElement.innerHTML = await response.text()
    } else {
        window.location = '/login?message=Password+reset+successful.'
    }
}