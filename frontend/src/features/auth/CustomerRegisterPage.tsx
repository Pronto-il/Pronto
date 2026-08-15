import { useNavigate } from 'react-router-dom';
import { PageHeader } from '../../shared/components';
import { CustomerRegisterForm } from './CustomerRegisterForm';

export default function CustomerRegisterPage() {
  const navigate = useNavigate();

  return (
    <div className="focused-page">
      <PageHeader title="הרשמה כלקוח" onBack={() => navigate('/register')} />
      <CustomerRegisterForm
        onSuccess={(email) => navigate(`/verify?email=${encodeURIComponent(email)}`)}
      />
    </div>
  );
}
