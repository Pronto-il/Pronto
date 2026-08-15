import { useSearchParams } from 'react-router-dom';
import { PageHeader } from '../../shared/components';
import { VerifyCodeForm } from './VerifyCodeForm';

export default function VerifyPage() {
  const [searchParams] = useSearchParams();
  const email = searchParams.get('email') ?? '';

  return (
    <div className="focused-page">
      <PageHeader title="אימות כתובת אימייל" description="הזינו את הקוד שנשלח לאימייל שלכם" />
      <VerifyCodeForm initialEmail={email} />
    </div>
  );
}
