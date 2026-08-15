import { useSearchParams } from 'react-router-dom';
import { PageHeader } from '../../shared/components';
import { LoginForm } from './LoginForm';

export default function LoginPage() {
  const [searchParams] = useSearchParams();
  const email = searchParams.get('email') ?? '';

  return (
    <div className="focused-page">
      <PageHeader title="התחברות" />
      <LoginForm initialEmail={email} />
    </div>
  );
}
