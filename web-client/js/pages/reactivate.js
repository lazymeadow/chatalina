$(function () {
    $('form').on('submit', async function (event) {
        event.preventDefault();

        const data = Object.fromEntries(new FormData(this));
        const response = await fetch("/reactivate", {
            method: "POST",
            body: JSON.stringify(data),
            headers: {"Content-Type": "application/json"}
        });
        if (!response.ok) {
            const message = await response.text()
            const messageElement = $('#message');
            messageElement.addClass('error')
            messageElement.text(message || 'There was a problem requesting account reactivation.')
        } else {
            const query = new URLSearchParams({
                message: "Reactivation request sent.",
                parasite: data.parasite
            })
            window.location = `/login?${query.toString()}`
        }
    });
});
