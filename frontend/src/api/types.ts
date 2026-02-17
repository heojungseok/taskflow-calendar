// ===== API 공통 응답 타입 =====
// 백엔드 ApiResponse.java 기준: { success, data, error }

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  error?: {
    code: string;
    message: string;
  };
}
