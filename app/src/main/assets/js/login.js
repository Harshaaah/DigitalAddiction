function login() {
    const username = document.getElementById("username").value;
    const password = document.getElementById("password").value;
    const error = document.getElementById("error");

    if (username === "" || password === "") {
        error.innerText = "Please enter all fields";
        return;
    }

    // Demo credentials
    if (username === "admin" && password === "1234") {
        window.location.href = "dashboard.html";
    } else {
        error.innerText = "Invalid username or password";
    }
}
