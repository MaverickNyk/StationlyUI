import { initializeApp } from "firebase/app";
import {
    getAuth,
    createUserWithEmailAndPassword,
    signInWithEmailAndPassword,
    signOut,
    onAuthStateChanged,
    deleteUser,
    updateProfile,
    signInWithPopup,
    GoogleAuthProvider,
    sendPasswordResetEmail
} from "firebase/auth";
import { getFirestore, doc, setDoc, getDoc, updateDoc } from "firebase/firestore";
import { firebaseConfig } from "./firebase-config";

// Initialize Firebase
const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getFirestore(app);
const googleProvider = new GoogleAuthProvider();

export const authService = {
    // Sign Up w/ Email
    register: async (email, password, displayName) => {
        try {
            const userCredential = await createUserWithEmailAndPassword(auth, email, password);
            const user = userCredential.user;

            // Update Auth Profile
            if (displayName) {
                await updateProfile(user, { displayName });
            }

            // Create User Config Object in Firestore
            await setDoc(doc(db, "users", user.uid), {
                email: user.email,
                name: displayName || "",
                mobile: "",
                address: "",
                location: "",
                createdAt: new Date().toISOString()
            });

            return { user: user, error: null };
        } catch (error) {
            return { user: null, error: error.message };
        }
    },

    // Get Extended User Data
    getUserData: async (uid) => {
        try {
            const docRef = doc(db, "users", uid);
            const docSnap = await getDoc(docRef);
            if (docSnap.exists()) {
                return { data: docSnap.data(), error: null };
            } else {
                return { data: null, error: "Profile not found" };
            }
        } catch (error) {
            return { data: null, error: error.message };
        }
    },

    // Update User Data
    updateUserData: async (uid, data) => {
        try {
            await updateDoc(doc(db, "users", uid), data);
            return { success: true, error: null };
        } catch (error) {
            return { success: false, error: error.message };
        }
    },

    // Login
    login: async (email, password) => {
        try {
            const userCredential = await signInWithEmailAndPassword(auth, email, password);
            return { user: userCredential.user, error: null };
        } catch (error) {
            return { user: null, error: error.message };
        }
    },

    // Login w/ Google
    loginWithGoogle: async () => {
        try {
            const result = await signInWithPopup(auth, googleProvider);
            const user = result.user;

            // Allow merge to avoid overwriting existing data if just logging in
            // But setDoc without merge overwrites. Use getDoc first or set with merge.
            // A simpler way for this demo:
            await setDoc(doc(db, "users", user.uid), {
                email: user.email,
                name: user.displayName || "",
            }, { merge: true });

            return { user: user, error: null };
        } catch (error) {
            return { user: null, error: error.message };
        }
    },

    // Logout
    logout: async () => {
        try {
            await signOut(auth);
            return { success: true, error: null };
        } catch (error) {
            return { success: false, error: error.message };
        }
    },

    // Reset Password
    resetPassword: async (email) => {
        try {
            await sendPasswordResetEmail(auth, email);
            return { success: true, error: null };
        } catch (error) {
            return { success: false, error: error.message };
        }
    },


    // Delete Account
    deleteAccount: async () => {
        const user = auth.currentUser;
        if (!user) return { success: false, error: "No user logged in" };

        try {
            await deleteUser(user);
            return { success: true, error: null };
        } catch (error) {
            return { success: false, error: error.message };
        }
    },

    // State Observer
    onStateChange: (callback) => {
        return onAuthStateChanged(auth, callback);
    },

    // Get Current User
    getCurrentUser: () => {
        return auth.currentUser;
    }
};
