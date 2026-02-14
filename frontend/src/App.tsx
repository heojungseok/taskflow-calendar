import { BrowserRouter, Routes, Route, Navigate } from 'react-router';
import Login from './pages/Login';
import { useAuthStore } from './store/authStore';

function App() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route
          path="/"
          element={
            isAuthenticated ? (
              <div className="p-4">
                <h1 className="text-2xl font-bold">TaskFlow Calendar</h1>
                <p className="mt-2">Task 관리 화면 (곧 구현)</p>
              </div>
            ) : (
              <Navigate to="/login" replace />
            )
          }
        />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
