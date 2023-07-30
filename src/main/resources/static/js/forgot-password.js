async function handleForgotPassword() {
    const data = JSON.stringify(Object.fromEntries(new FormData(document.getElementById('forgot-password'))));
    const response = await fetch("/forgot-password", {
        method: "POST",
        body: data,
        headers: {"Content-Type": "application/json"}
    });
    if (!response.ok) {
        const messageElement = document.getElementById('message')
        messageElement.classList.add("error")
        messageElement.innerHTML = await response.text()
    } else {
        window.location = '/login?message=A+password+reset+link+has+been+sent+to+your+email.+Check+your+spam+folder!'
    }
}