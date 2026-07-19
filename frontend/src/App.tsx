import { Navigate, Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { ModelSettingsPage } from './pages/ModelSettingsPage'
import { ResumeDetailPage } from './pages/ResumeDetailPage'
import { ResumeListPage } from './pages/ResumeListPage'
import { InterviewCreatePage } from './pages/InterviewCreatePage'
import { InterviewHistoryPage } from './pages/InterviewHistoryPage'
import { InterviewLivePage } from './pages/InterviewLivePage'
import { InterviewReportPage } from './pages/InterviewReportPage'
import { KnowledgeBasePage } from './pages/KnowledgeBasePage'

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Navigate replace to="/resumes" />} />
        <Route path="/resumes" element={<ResumeListPage />} />
        <Route path="/resumes/:resumeId" element={<ResumeDetailPage />} />
        <Route path="/interviews/new" element={<InterviewCreatePage />} />
        <Route path="/interviews" element={<InterviewHistoryPage />} />
        <Route path="/interviews/:sessionId" element={<InterviewLivePage />} />
        <Route path="/interviews/:sessionId/report" element={<InterviewReportPage />} />
        <Route path="/knowledge" element={<KnowledgeBasePage />} />
        <Route path="/settings" element={<ModelSettingsPage />} />
        <Route path="*" element={<Navigate replace to="/resumes" />} />
      </Route>
    </Routes>
  )
}
