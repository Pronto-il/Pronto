import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../../shared/components';
import { ProfessionalRegisterForm } from './ProfessionalRegisterForm';

export default function ProfessionalRegisterPage() {
  const navigate = useNavigate();

  return (
    <div className="focused-page">
      <PageHeader title="הרשמה כבעל מקצוע" onBack={() => navigate('/register')} />
      <ProfessionalRegisterForm
        onSuccess={(email) => navigate(`/verify?email=${encodeURIComponent(email)}`)}
      />
    </div>
  );
}
