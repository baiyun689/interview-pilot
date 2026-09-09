import { Navigate, Route, Routes } from 'react-router-dom'
import { ProtectedRoute, PublicOnlyRoute } from './auth/ProtectedRoute'
import { Layout } from './components/Layout'
import { ModelSettingsPage } from './pages/ModelSettingsPage'
import { ResumeDetailPage } from './pages/ResumeDetailPage'
import { ResumeListPage } from './pages/ResumeListPage'
import { InterviewCreatePage } from './pages/InterviewCreatePage'
import { InterviewHistoryPage } from './pages/InterviewHistoryPage'
import { InterviewLivePage } from './pages/InterviewLivePage'
import { InterviewReportPage } from './pages/InterviewReportPage'
import { KnowledgeBasePage } from './pages/KnowledgeBasePage'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { EnterprisePage } from './pages/EnterprisePage'
import { HiringJobsPage, HiringJobDetailPage } from './pages/HiringJobsPage'
import { CandidateApplicationsPage } from './pages/CandidateApplicationsPage'
import { HiringPlatformPage } from './pages/HiringPlatformPage'
import { CandidateInvitationsPage } from './pages/CandidateInvitationsPage'
import { NotificationsPage } from './pages/NotificationsPage'

export function App() {
  return (
    <Routes>
      <Route path="/jobs" element={<div className="hiring-public"><HiringJobsPage /></div>} />
      <Route path="/jobs/:jobId" element={<div className="hiring-public"><HiringJobDetailPage /></div>} />
      <Route path="/login" element={<PublicOnlyRoute><LoginPage /></PublicOnlyRoute>} />
      <Route path="/register" element={<PublicOnlyRoute><RegisterPage /></PublicOnlyRoute>} />
      <Route element={<ProtectedRoute><Layout /></ProtectedRoute>}>
        <Route path="/enterprise" element={<EnterprisePage />} />
        <Route path="/platform" element={<HiringPlatformPage />} />
        <Route path="/candidate/applications" element={<CandidateApplicationsPage />} />
        <Route path="/candidate/invitations" element={<CandidateInvitationsPage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route index element={<Navigate replace to="/resumes" />} />
        <Route path="/resumes" element={<ResumeListPage />} />
        <Route path="/resumes/:resumeId" element={<ResumeDetailPage />} />
        <Route path="/interviews/new" element={<InterviewCreatePage />} />
        <Route path="/interviews" element={<InterviewHistoryPage />} />
        <Route path="/interviews/:sessionId" element={<InterviewLivePage />} />
        <Route path="/interviews/:sessionId/report" element={<InterviewReportPage />} />
        <Route path="/knowledge" element={<KnowledgeBasePage />} />
        <Route path="/settings" element={<ModelSettingsPage />} />
      </Route>
      <Route path="*" element={<Navigate replace to="/resumes" />} />
    </Routes>
  )
}
