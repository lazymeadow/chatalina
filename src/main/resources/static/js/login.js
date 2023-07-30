window.onload = () => {
    const last_login = localStorage.getItem('last_login');
    if (last_login) {
        document.getElementById('parasite').setAttribute("value", last_login);
        document.getElementById('remember').setAttribute('checked', true);
    }
}

async function handleLogin() {
    const data = Object.fromEntries(new FormData(document.getElementById('login')));
    if (document.getElementById('remember').checked) {
        localStorage.setItem('last_login', data.parasite);
    } else {
        localStorage.removeItem('last_login');
    }
    const response = await fetch("/login", {
        method: "POST",
        body: JSON.stringify(data),
        headers: {"Content-Type": "application/json"}
    });
    if (!response.ok) {
        let message
        if (response.status === 401) {
            message = "Invalid username or password."
        } else {
            message = await response.text()
        }
        const messageElement = document.getElementById('message')
        messageElement.classList.add("error")
        messageElement.innerHTML = message
    } else {
        window.location = '/'
    }
}