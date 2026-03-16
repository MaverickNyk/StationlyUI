import { authService } from './auth-service.js';

class AuthUI {
    constructor() {
        this.dom = {
            authTab: document.getElementById('auth-tab'),
            profileSection: document.getElementById('profile-section'),
            loginForm: document.getElementById('login-form'),
            registerForm: document.getElementById('register-form'),
            resetForm: document.getElementById('reset-form'),
            resetMessage: document.getElementById('reset-message'),
            userEmail: document.getElementById('user-display-email'),
            userAvatar: document.getElementById('user-avatar'),
            navAuthBtn: document.getElementById('nav-auth-btn'),

            // Profile Inputs
            profileName: document.getElementById('profile-name'),
            profileMobile: document.getElementById('profile-mobile'),
            profileAddress: document.getElementById('profile-address'),
            profileLocation: document.getElementById('profile-location'),
            updateBtn: document.getElementById('update-profile-btn'),
            profileStatus: document.getElementById('profile-status')
        };

        this.init();
    }

    init() {
        this.bindEvents();
        authService.onStateChange(this.handleUserState.bind(this));
    }

    bindEvents() {
        // Toggle Sign Up / Login
        document.getElementById('show-register')?.addEventListener('click', (e) => {
            e.preventDefault();
            this.dom.loginForm.parentElement.style.display = 'none';
            this.dom.registerForm.parentElement.style.display = 'block';
        });

        document.getElementById('show-login')?.addEventListener('click', (e) => {
            e.preventDefault();
            this.dom.registerForm.parentElement.style.display = 'none';
            this.dom.resetForm.parentElement.style.display = 'none';
            this.dom.loginForm.parentElement.style.display = 'block';
        });

        document.getElementById('show-reset-password')?.addEventListener('click', (e) => {
            e.preventDefault();
            this.dom.loginForm.parentElement.style.display = 'none';
            this.dom.resetForm.parentElement.style.display = 'block';
            this.dom.resetMessage.style.display = 'none';
        });

        document.getElementById('back-to-login')?.addEventListener('click', (e) => {
            e.preventDefault();
            this.dom.resetForm.parentElement.style.display = 'none';
            this.dom.loginForm.parentElement.style.display = 'block';
        });

        // Forms
        this.dom.loginForm?.addEventListener('submit', this.handleLogin.bind(this));
        this.dom.registerForm?.addEventListener('submit', this.handleRegister.bind(this));
        this.dom.resetForm?.addEventListener('submit', this.handleResetPassword.bind(this));

        // Actions
        document.getElementById('logout-btn')?.addEventListener('click', this.handleLogout.bind(this));
        document.getElementById('delete-account-btn')?.addEventListener('click', this.handleDelete.bind(this));
        document.getElementById('google-login-btn')?.addEventListener('click', this.handleGoogleLogin.bind(this));
        this.dom.updateBtn?.addEventListener('click', this.handleUpdateProfile.bind(this));
    }

    async handleLogin(e) {
        e.preventDefault();
        const email = e.target.email.value;
        const password = e.target.password.value;
        const errorEl = document.getElementById('login-error');

        const { user, error } = await authService.login(email, password);
        if (error) {
            console.error("Login Error:", error);
            if (error.includes('auth/invalid-email') || error.includes('auth/user-not-found') || error.includes('auth/wrong-password')) {
                errorEl.textContent = "Invalid email or password.";
            } else {
                errorEl.textContent = "Unable to sign in. Please try again.";
            }
            errorEl.style.display = 'block';
        } else {
            console.log('Logged in:', user);
            // Optionally redirect or switch tab
            switchTab('home');
        }
    }

    async handleGoogleLogin(e) {
        e.preventDefault();
        const errorEl = document.getElementById('login-error');

        const { user, error } = await authService.loginWithGoogle();
        if (error) {
            console.error("Google Login Error:", error); // Log real error for developers

            if (error.includes('popup-closed-by-user')) {
                errorEl.textContent = "Sign-in cancelled.";
            } else {
                // Hide technical details (DNS, Config, etc) from user
                errorEl.textContent = "Unable to sign in with Google. Please try again later.";
            }
            errorEl.style.display = 'block';
        } else {
            console.log('Logged in with Google:', user);
            switchTab('home');
        }
    }

    async handleRegister(e) {
        e.preventDefault();
        const email = e.target.email.value;
        const password = e.target.password.value;
        const name = e.target.name.value;
        const errorEl = document.getElementById('register-error');

        const { user, error } = await authService.register(email, password, name);
        if (error) {
            console.error("Register Error:", error);
            if (error.includes('email-already-in-use')) {
                errorEl.textContent = "This email is already registered. Please sign in.";
            } else if (error.includes('weak-password')) {
                errorEl.textContent = "Password should be at least 6 characters.";
            } else {
                errorEl.textContent = "Unable to create account. Please try again.";
            }
            errorEl.style.display = 'block';
        } else {
            console.log('Registered:', user);
            switchTab('home');
        }
    }

    async handleResetPassword(e) {
        e.preventDefault();
        const email = e.target.email.value;
        const msgEl = this.dom.resetMessage;

        msgEl.textContent = "Sending...";
        msgEl.style.color = "#fff";
        msgEl.style.display = 'block';

        const { success, error } = await authService.resetPassword(email);

        if (success) {
            msgEl.textContent = "Check your email inbox to reset your password.";
            msgEl.style.color = "#4caf50";
        } else {
            console.error("Reset Error:", error);
            if (error.includes('user-not-found')) {
                // Security best practice usually suggests saying "If an account exists...",
                // but for UX in this app we can be slightly specific or just generic.
                msgEl.textContent = "We couldn't find an account with that email.";
            } else {
                msgEl.textContent = "Unable to send reset link. Please check the email.";
            }
            msgEl.style.color = "#ff4d4d";
        }
    }

    async handleLogout() {
        await authService.logout();
        switchTab('auth'); // Go back to login screen
    }

    async handleDelete() {
        if (confirm("Are you sure you want to delete your account? This cannot be undone.")) {
            const { success, error } = await authService.deleteAccount();
            if (!success) alert(error);
        }
    }

    async handleUpdateProfile() {
        const user = authService.getCurrentUser();
        if (!user) return;

        this.dom.updateBtn.textContent = 'Saving...';
        this.dom.profileStatus.textContent = '';
        this.dom.profileStatus.style.color = '#fff';

        const data = {
            name: this.dom.profileName.value,
            mobile: this.dom.profileMobile.value,
            address: this.dom.profileAddress.value,
            location: this.dom.profileLocation.value
        };

        const { success, error } = await authService.updateUserData(user.uid, data);

        this.dom.updateBtn.textContent = 'Save Changes';

        if (success) {
            this.dom.profileStatus.textContent = 'Profile updated successfully!';
            this.dom.profileStatus.style.color = '#4caf50';
        } else {
            this.dom.profileStatus.textContent = 'Error: ' + error;
            this.dom.profileStatus.style.color = '#ff4d4d';
        }
    }

    async handleUserState(user) {
        if (user) {
            // Logged In
            this.dom.profileSection.style.display = 'block';
            this.dom.loginForm.parentElement.style.display = 'none';
            this.dom.registerForm.parentElement.style.display = 'none';

            // Update UI
            this.dom.userEmail.textContent = user.email;

            // Set Nav Avatar (Always Initials)
            const initial = (user.displayName || user.email || 'U')[0].toUpperCase();

            // Clear text and set classes for Avatar mode
            this.dom.navAuthBtn.textContent = initial;
            this.dom.navAuthBtn.classList.add('nav-avatar-btn');

            // Set Large Profile Avatar
            this.dom.userAvatar.textContent = initial;

            // Ensure we reset any previous image content if switching users/modes (removes the image if it existed)
            if (this.dom.userAvatar.parentElement.querySelector('img')) {
                this.dom.userAvatar.parentElement.innerHTML = '<span id="user-avatar">' + initial + '</span>';
                this.dom.userAvatar = document.getElementById('user-avatar'); // Re-bind
            }

            // Also ensure nav button doesn't have an image inside
            const navImg = this.dom.navAuthBtn.querySelector('img');
            if (navImg) {
                this.dom.navAuthBtn.removeChild(navImg);
                this.dom.navAuthBtn.textContent = initial;
            }

            // Load extended data
            const { data } = await authService.getUserData(user.uid);
            if (data) {
                this.dom.profileName.value = data.name || user.displayName || '';
                this.dom.profileMobile.value = data.mobile || '';
                this.dom.profileAddress.value = data.address || '';
                this.dom.profileLocation.value = data.location || '';
            }

        } else {
            // Logged Out
            this.dom.profileSection.style.display = 'none';
            this.dom.loginForm.parentElement.style.display = 'block';
            this.dom.registerForm.parentElement.style.display = 'none';

            this.dom.navAuthBtn.textContent = 'Login';
            this.dom.navAuthBtn.classList.remove('nav-avatar-btn');
            this.dom.navAuthBtn.innerHTML = 'Login'; // Ensure image is gone
        }
    }
}

// Global scope initialization or export
export const authUI = new AuthUI();
