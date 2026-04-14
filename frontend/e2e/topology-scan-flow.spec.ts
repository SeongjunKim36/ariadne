import { expect, test } from '@playwright/test';

type ScanStatusResponse = {
  scanId: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  startedAt: string;
  completedAt: string | null;
  totalNodes: number;
  totalEdges: number;
  durationMs: number;
  errorMessage: string | null;
  warningMessage: string | null;
};

const emptyGraph = {
  nodes: [],
  edges: [],
  metadata: {
    totalNodes: 0,
    totalEdges: 0,
    collectedAt: null,
    scanDurationMs: 0,
  },
};

const populatedGraph = {
  nodes: [
    {
      id: 'arn:aws:ec2:ap-northeast-2:123456789012:vpc/vpc-1234',
      type: 'vpc-group',
      parentNode: null,
      data: {
        arn: 'arn:aws:ec2:ap-northeast-2:123456789012:vpc/vpc-1234',
        resourceId: 'vpc-1234',
        resourceType: 'VPC',
        name: 'prod-main-vpc',
        environment: 'prod',
        cidrBlock: '10.0.0.0/16',
      },
    },
    {
      id: 'arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-app-a',
      type: 'subnet-group',
      parentNode: 'arn:aws:ec2:ap-northeast-2:123456789012:vpc/vpc-1234',
      data: {
        arn: 'arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-app-a',
        resourceId: 'subnet-app-a',
        resourceType: 'SUBNET',
        name: 'prod-app-a',
        environment: 'prod',
        availabilityZone: 'ap-northeast-2a',
      },
    },
    {
      id: 'arn:aws:rds:ap-northeast-2:123456789012:db:prod-db-1',
      type: 'rds',
      parentNode: null,
      data: {
        arn: 'arn:aws:rds:ap-northeast-2:123456789012:db:prod-db-1',
        resourceId: 'prod-db-1',
        resourceType: 'RDS',
        name: 'prod-db-1',
        environment: 'prod',
        endpoint: 'prod-db.cluster.local:5432',
        engine: 'postgres',
        instanceClass: 'db.t3.micro',
        status: 'available',
      },
    },
    {
      id: 'arn:aws:ec2:ap-northeast-2:123456789012:instance/i-prod-api-1',
      type: 'ec2',
      parentNode: 'arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-app-a',
      data: {
        arn: 'arn:aws:ec2:ap-northeast-2:123456789012:instance/i-prod-api-1',
        resourceId: 'i-prod-api-1',
        resourceType: 'EC2',
        name: 'prod-api-1',
        environment: 'prod',
        instanceType: 't3.micro',
        privateIp: '10.0.1.10',
        state: 'running',
      },
    },
  ],
  edges: [
    {
      id: 'arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-app-a-BELONGS_TO-arn:aws:ec2:ap-northeast-2:123456789012:vpc/vpc-1234',
      source: 'arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-app-a',
      target: 'arn:aws:ec2:ap-northeast-2:123456789012:vpc/vpc-1234',
      type: 'BELONGS_TO',
      data: {},
    },
    {
      id: 'arn:aws:ec2:ap-northeast-2:123456789012:instance/i-prod-api-1-BELONGS_TO-arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-app-a',
      source: 'arn:aws:ec2:ap-northeast-2:123456789012:instance/i-prod-api-1',
      target: 'arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-app-a',
      type: 'BELONGS_TO',
      data: {},
    },
    {
      id: 'arn:aws:ec2:ap-northeast-2:123456789012:instance/i-prod-api-1-LIKELY_USES-arn:aws:rds:ap-northeast-2:123456789012:db:prod-db-1',
      source: 'arn:aws:ec2:ap-northeast-2:123456789012:instance/i-prod-api-1',
      target: 'arn:aws:rds:ap-northeast-2:123456789012:db:prod-db-1',
      type: 'LIKELY_USES',
      data: {
        confidence: 'medium',
        reason: 'shared-sg-db-port',
      },
    },
  ],
  metadata: {
    totalNodes: 4,
    totalEdges: 3,
    collectedAt: '2026-04-14T03:00:00Z',
    scanDurationMs: 183000,
  },
};

const resourceDetail = {
  resource: {
    id: 'arn:aws:rds:ap-northeast-2:123456789012:db:prod-db-1',
    type: 'rds',
    parentNode: null,
    data: {
      arn: 'arn:aws:rds:ap-northeast-2:123456789012:db:prod-db-1',
      resourceId: 'prod-db-1',
      resourceType: 'RDS',
      name: 'prod-db-1',
      endpoint: 'prod-db.cluster.local:5432',
      engine: 'postgres',
      environment: 'prod',
    },
  },
  connections: [
    {
      direction: 'incoming',
      relationshipType: 'LIKELY_USES',
      relationshipData: {
        confidence: 'medium',
        port: 5432,
      },
      node: {
        id: 'arn:aws:ec2:ap-northeast-2:123456789012:instance/i-prod-api-1',
        type: 'ec2',
        parentNode: 'arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-app-a',
        data: {
          arn: 'arn:aws:ec2:ap-northeast-2:123456789012:instance/i-prod-api-1',
          resourceId: 'i-prod-api-1',
          resourceType: 'EC2',
          name: 'prod-api-1',
        },
      },
    },
  ],
};

test('runs a scan flow and renders inferred database links', async ({ page }) => {
  let graphVersion = 0;
  let latestScan: ScanStatusResponse | null = null;
  let statusPollCount = 0;

  const runningScan: ScanStatusResponse = {
    scanId: 'scan-prod-001',
    status: 'RUNNING',
    startedAt: '2026-04-14T03:00:00Z',
    completedAt: null,
    totalNodes: 0,
    totalEdges: 0,
    durationMs: 0,
    errorMessage: null,
    warningMessage: null,
  };

  const completedScan: ScanStatusResponse = {
    scanId: 'scan-prod-001',
    status: 'COMPLETED',
    startedAt: '2026-04-14T03:00:00Z',
    completedAt: '2026-04-14T03:03:03Z',
    totalNodes: 4,
    totalEdges: 3,
    durationMs: 183000,
    errorMessage: null,
    warningMessage: 'S3 collector skipped an empty notification configuration.',
  };

  await page.route('**/api/scan/preflight', async (route) => {
    await route.fulfill({
      json: {
        ready: true,
        region: 'ap-northeast-2',
        accountId: '123456789012',
        callerArn: 'arn:aws:sts::123456789012:assumed-role/Ariadne/dev',
        authenticationMode: 'default-chain',
        message: 'AWS credentials are ready for scanning.',
      },
    });
  });

  await page.route('**/api/scan/latest', async (route) => {
    if (!latestScan) {
      await route.fulfill({ status: 204, body: '' });
      return;
    }
    await route.fulfill({ json: latestScan });
  });

  await page.route('**/api/graph', async (route) => {
    await route.fulfill({ json: graphVersion === 0 ? emptyGraph : populatedGraph });
  });

  await page.route('**/api/resources?*', async (route) => {
    await route.fulfill({ json: resourceDetail });
  });

  await page.route('**/api/scan', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.fallback();
      return;
    }

    latestScan = runningScan;
    statusPollCount = 0;
    await route.fulfill({ status: 202, json: runningScan });
  });

  await page.route('**/api/scan/scan-prod-001/status', async (route) => {
    statusPollCount += 1;
    if (statusPollCount >= 2) {
      latestScan = completedScan;
      graphVersion = 1;
      await route.fulfill({ json: completedScan });
      return;
    }
    await route.fulfill({ json: runningScan });
  });

  await page.goto('/');

  await expect(page.getByTestId('scan-status-banner')).toContainText('AWS READY');
  await expect(page.getByTestId('scan-status-banner')).toContainText('No scan has been run');
  await expect(page.getByTestId('topology-canvas')).toContainText('No nodes match');

  await page.getByTestId('start-scan-button').click();

  await expect(page.getByTestId('scan-status-banner')).toContainText('RUNNING');
  await expect(page.getByTestId('start-scan-button')).toBeDisabled();
  await expect(page.getByTestId('scan-status-banner')).toContainText('COMPLETED', { timeout: 10_000 });
  await expect(page.getByTestId('scan-status-banner')).toContainText('S3 collector skipped');

  const canvas = page.getByTestId('topology-canvas');
  await expect(canvas.locator('.topology-group-label', { hasText: 'prod-main-vpc' })).toBeVisible();
  await expect(canvas.locator('.topology-node-label', { hasText: 'prod-api-1' })).toBeVisible();
  await expect(canvas.locator('.topology-node-label', { hasText: 'prod-db-1' })).toBeVisible();

  await canvas.locator('.topology-node-label', { hasText: 'prod-db-1' }).click();
  await expect(page.getByTestId('detail-panel')).toContainText('LIKELY_USES');
  await expect(page.getByTestId('detail-panel')).toContainText('prod-api-1');
});

test('blocks scan actions when AWS preflight is not ready', async ({ page }) => {
  await page.route('**/api/scan/preflight', async (route) => {
    await route.fulfill({
      json: {
        ready: false,
        region: 'ap-northeast-2',
        accountId: null,
        callerArn: null,
        authenticationMode: 'default-chain',
        message: 'AWS credentials are missing or the local SSO session expired. Run `aws sso login` or set static credentials before scanning.',
      },
    });
  });

  await page.route('**/api/scan/latest', async (route) => {
    await route.fulfill({ status: 204, body: '' });
  });

  await page.route('**/api/graph', async (route) => {
    await route.fulfill({ json: emptyGraph });
  });

  await page.goto('/');

  await expect(page.getByTestId('scan-status-banner')).toContainText('AWS BLOCKED');
  await expect(page.getByTestId('scan-status-banner')).toContainText('aws sso login');
  await expect(page.getByTestId('start-scan-button')).toBeDisabled();
});
